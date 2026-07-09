package net.thewesthill.agent.reporter;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import net.thewesthill.agent.collector.SpanCollector;
import net.thewesthill.agent.collector.SpanEvent;
import net.thewesthill.agent.log.AgentLog;

/**
 * Background reporter that drains completed spans from {@link SpanCollector} and persists them to a
 * SQLite database.
 * <p>
 * This is the single consumer / single writer side of the
 * {@link net.thewesthill.agent.collector.SpanSink} pipeline: {@code SpanSink} decouples producers
 * (intercepted business threads) from this reporter, which is the only thread that touches
 * storage.
 * <p>
 * Lifecycle, driven from {@code TraceAgent.premain}:
 * <ul>
 *   <li>{@link #SqliteReporter(String)} — stores the DB path and creates the worker executor; does
 *       not connect yet.</li>
 *   <li>{@link #start()} — opens the connection and schema, then launches the loop on a daemon
 *       thread named {@code "spindle-reporter"}.</li>
 *   <li>{@link #close()} — stops the worker, flushes any remaining spans, and closes the JDBC
 *       resources. Registered as a JVM shutdown hook.</li>
 * </ul>
 * <p>
 * Failure handling: a connection/setup failure sets {@link #disabled} immediately; after
 * {@value #MAX_FAILURES} consecutive batch-write failures the reporter also disables itself. Once
 * disabled, {@link #writeBatch} is a no-op, so further spans are drained and discarded rather than
 * crashing the agent.
 *
 * @author Jonah Fan
 * @since 1.0.0
 */
public final class SqliteReporter implements AutoCloseable {

  /**
   * Maximum number of spans drained and written per loop iteration.
   */
  private static final int BATCH_SIZE = 200;

  /**
   * Sleep duration of the reporter loop when no spans are available, in milliseconds.
   */
  private static final long IDLE_SLEEP_MS = 100;

  /**
   * Consecutive batch-write failures after which the reporter disables itself.
   */
  private static final int MAX_FAILURES = 5;

  /**
   * Maximum time to wait for the reporter thread to terminate on shutdown, in seconds.
   */
  private static final int SHUTDOWN_TIMEOUT_SEC = 10;

  /**
   * DDL creating the {@code span} table if it does not already exist.
   */
  private static final String CREATE_TABLE_SQL = """
        CREATE TABLE IF NOT EXISTS span (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            trace_id TEXT NOT NULL,
            span_id TEXT NOT NULL,
            parent_id TEXT,
            span_name TEXT NOT NULL,
            span_kind TEXT NOT NULL,
            start_time_ms INTEGER NOT NULL,
            duration_us INTEGER NOT NULL,
            status TEXT NOT NULL,
            exception_class TEXT,
            exception_msg TEXT,
            stack_trace TEXT,
            attributes TEXT
        )
      
      """;

  /**
   * Index on {@code trace_id} to accelerate trace-tree lookups.
   */
  private static final String CREATE_IDX_TRACE_SQL =
      "CREATE INDEX IF NOT EXISTS idx_trace ON span(trace_id)";

  /**
   * Index on {@code start_time_ms DESC} to accelerate recent-spans queries.
   */
  private static final String CREATE_IDX_START_SQL =
      "CREATE INDEX IF NOT EXISTS idx_start ON span(start_time_ms DESC)";

  /**
   * Parameterized insert that materializes one {@link SpanEvent} as a {@code span} row.
   */
  private static final String INSERT_SQL = """
      INSERT INTO span (
          trace_id, span_id, parent_id, span_name, span_kind,
          start_time_ms, duration_us, status,
          exception_class, exception_msg, stack_trace, attributes
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      """;

  /**
   * Absolute path of the SQLite database file.
   */
  private final String dbPath;

  /**
   * Running count of consecutive batch-write failures; reset to {@code 0} on a successful commit.
   */
  private final AtomicInteger failureCount = new AtomicInteger(0);

  /**
   * Single-thread executor owning the {@code "spindle-reporter"} daemon worker.
   */
  private final ExecutorService executor;

  /**
   * Latch flag, {@code volatile} for cross-thread visibility: set when initialization or repeated
   * write failures disable the reporter. Once {@code true}, {@link #writeBatch} is a no-op.
   */
  private volatile boolean disabled = false;

  /**
   * The single JDBC connection used for all writes; {@code null} until {@link #start} succeeds.
   */
  private Connection conn;

  /**
   * The reused insert statement (manual transaction mode); {@code null} until {@link #start}
   * succeeds.
   */
  private PreparedStatement insertStmt;

  /**
   * Creates a reporter targeting the given SQLite database file.
   * <p>
   * Does not connect yet — connection and schema setup happen in {@link #start}. The parent
   * directory of {@code dbPath} is created on demand at connect time.
   *
   * @param dbPath the path to the SQLite database file; must not be {@code null}
   */
  public SqliteReporter(String dbPath) {
    this.dbPath = Objects.requireNonNull(dbPath, "dbPath must not be null");
    this.executor = Executors.newSingleThreadExecutor(r -> {
      Thread t = new Thread(r, "spindle-reporter");
      t.setDaemon(true);
      return t;
    });
  }

  /**
   * Truncates a string to at most {@code max} characters, returning it unchanged when it is
   * {@code null} or already no longer than {@code max}.
   *
   * @param s   the string to truncate, or {@code null}
   * @param max the maximum number of characters to keep
   * @return the (possibly truncated) string, or {@code null} if {@code s} was {@code null}
   */
  @SuppressWarnings("SameParameterValue")
  private static String truncate(String s, int max) {

    return (s == null || s.length() < max) ? s : s.substring(0, max);
  }

  /**
   * Sleeps for the given duration, restoring the interrupt flag if interrupted.
   * <p>
   * Used by {@link #runLoop} as the 1-second backoff after a caught loop exception. Only ever
   * invoked with a single value ({@code 1000}), hence
   * {@code @SuppressWarnings("SameParameterValue")}.
   *
   * @param ms the duration to sleep, in milliseconds
   */
  @SuppressWarnings("SameParameterValue")
  private static void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Closes an {@link AutoCloseable} ignoring (but logging) any exception.
   * <p>
   * A no-op when {@code closeable} is {@code null}. Used during shutdown so a failing resource does
   * not prevent the rest from being released.
   *
   * @param closeable the resource to close, or {@code null}
   */
  private static void closeQuietly(AutoCloseable closeable) {
    if (closeable != null) {
      try {
        closeable.close();
      } catch (Exception e) {
        AgentLog.warn("Close Failed: " + closeable.getClass().getSimpleName(), e);
      }
    }
  }

  /**
   * Starts the reporter: opens the connection and schema, then launches the drain loop on the
   * worker thread.
   * <p>
   * Safe to call once; the loop runs until the worker is interrupted by {@link #close}.
   */
  public void start() {
    initConnect();
    executor.submit(this::runLoop);
  }

  /**
   * Shuts down the reporter: interrupts the worker, waits up to {@value #SHUTDOWN_TIMEOUT_SEC}s for
   * it to stop, flushes any spans still queued, then closes the JDBC resources.
   * <p>
   * Invoked by {@code TraceAgent}'s shutdown hook. Safe to call even if {@link #start} failed to
   * connect (resources are simply {@code null}).
   *
   * @throws Exception never thrown in practice; declared by {@link AutoCloseable}
   */
  @SuppressWarnings("RedundantThrows")
  @Override
  public void close() throws Exception {
    executor.shutdownNow();
    try {
      if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SEC, TimeUnit.SECONDS)) {
        AgentLog.warn("Reporter thread did not terminate gracefully");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    flushRemaining();
    closeQuietly(insertStmt);
    closeQuietly(conn);
  }

  /**
   * Opens the JDBC connection, creates the schema, and prepares the insert statement.
   * <p>
   * Loads the SQLite driver by name, falling back to the JDBC {@code ServiceLoader} if the class is
   * not found on the classpath; ensures the database's parent directory exists; runs in
   * manual-commit mode ({@code autoCommit = false}) so each batch is one transaction. On any
   * {@link SQLException} the reporter is marked {@link #disabled} and the partial connection is
   * closed.
   */
  @SuppressWarnings("ResultOfMethodCallIgnored")
  private void initConnect() {
    try {
      try {
        Class.forName("org.sqlite.JDBC");
      } catch (ClassNotFoundException e) {
        AgentLog.debug(
            "SQLite driver not found by name, relying on ServiceLoader: " + e.getMessage());
      }
      File dbFile = new File(dbPath);
      File parent = dbFile.getParentFile();
      if (parent != null && !parent.exists()) {
        parent.mkdirs();
      }

      conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
      // Must run outside a transaction (before setAutoCommit(false)); WAL is persistent and inherited by all later connections.
      enableWalMode(conn);
      conn.setAutoCommit(false);
      initSchema(conn);
      conn.commit();
      insertStmt = conn.prepareStatement(INSERT_SQL);
    } catch (SQLException e) {
      AgentLog.error("SQLite init failed, report disabled", e);
      disabled = true;
      closeQuietly(conn);
    }
  }

  /**
   * Switches the SQLite database to WAL (Write-Ahead Logging) journal mode for better concurrent
   * read/write throughput.
   * <p>
   * Runs {@code PRAGMA journal_mode=WAL} on the connection and verifies the returned mode string is
   * {@code "wal"}. When the pragma fails to apply — either the mode string is not {@code "wal"} or
   * {@link SQLException} is thrown — a warning is logged and the database keeps its default journal
   * mode; the caller (see {@link #initConnect}) proceeds regardless. Connection acquisition itself
   * is not affected.
   *
   * @param conn the connection whose journal mode to switch
   * @return {@code true} if WAL mode was enabled; {@code false} if the pragma failed or the mode
   * could not be confirmed, leaving the default journal mode in place
   */
  @SuppressWarnings("UnusedReturnValue")
  private boolean enableWalMode(Connection conn) {
    try (Statement st = conn.createStatement();
        ResultSet rs = st.executeQuery("PRAGMA journal_mode=WAL")) {
      boolean ok = rs.next() && "wal".equals(rs.getString(1));
      if (!ok) {
        AgentLog.warn("failed to enable WAL mode, falling back to default journal mode");
      }
      return ok;
    } catch (SQLException e) {
      AgentLog.warn("failed to enable WAL mode: ", e.getMessage());
      return false;
    }
  }

  /**
   * Creates the {@code span} table and its supporting indexes if they do not already exist.
   *
   * @param conn the connection to create the schema on
   * @throws SQLException if any DDL statement fails
   */
  private void initSchema(Connection conn) throws SQLException {
    try (Statement st = conn.createStatement()) {
      st.execute(CREATE_TABLE_SQL);
      st.execute(CREATE_IDX_TRACE_SQL);
      st.execute(CREATE_IDX_START_SQL);
    }
  }

  /**
   * The reporter worker loop: repeatedly drains a batch from {@link SpanCollector}, writes it, and
   * sleeps when idle.
   * <p>
   * Iteration logic:
   * <ul>
   *   <li>Drain up to {@link #BATCH_SIZE} spans; if any are present, {@link #writeBatch} them and
   *       loop again immediately (no sleep) to drain a backlog quickly.</li>
   *   <li>Otherwise, emit a warning per delta in {@link SpanCollector#adviceErrorCount} and sleep
   *       {@link #IDLE_SLEEP_MS}.</li>
   * </ul>
   * <p>
   * Interruption restores the interrupt flag and exits the loop; any other exception is logged and
   * the loop backs off for 1 second (via {@link #sleep}) before continuing. On exit the loop
   * performs a final {@link #flushRemaining}.
   */
  @SuppressWarnings("BusyWait")
  private void runLoop() {
    long lastAdviceErrorCount = 0;
    while (!Thread.currentThread().isInterrupted()) {
      try {
        List<SpanEvent> batch = SpanCollector.drain(BATCH_SIZE);
        if (!batch.isEmpty()) {
          writeBatch(batch);
          continue;
        }
        long currentErrors = SpanCollector.adviceErrorCount();
        if (currentErrors > lastAdviceErrorCount) {
          AgentLog.warn("advice error: +{} (total {})", currentErrors);
          lastAdviceErrorCount = currentErrors;
        }
        Thread.sleep(IDLE_SLEEP_MS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      } catch (Exception e) {
        AgentLog.warn("Reporter loop error, backing off 1s", e);
        sleep(1000);
      }
    }
    flushRemaining();
  }

  /**
   * Writes one batch: binds each {@link SpanEvent} to the prepared insert, executes the batch, and
   * commits, resetting the failure counter on success.
   * <p>
   * A no-op when {@link #disabled} or when the insert is not prepared. On {@link SQLException} the
   * failure counter is incremented, the transaction is rolled back, and after
   * {@value #MAX_FAILURES} consecutive failures the reporter disables itself.
   *
   * @param batch the spans to persist; not modified
   */
  private void writeBatch(List<SpanEvent> batch) {
    if (disabled || insertStmt == null) {
      return;
    }
    try {
      for (SpanEvent event : batch) {
        bindEvent(insertStmt, event);
        insertStmt.addBatch();
      }
      insertStmt.executeBatch();
      conn.commit();
      failureCount.set(0);
    } catch (SQLException e) {
      int failures = failureCount.incrementAndGet();
      AgentLog.warn("SQLite write failed ({}/{})", failures, MAX_FAILURES);
      rollbackQuietly();
      if (failures >= MAX_FAILURES) {
        disabled = true;
        AgentLog.error("SQLite reporter disabled after {} consecutive failures", MAX_FAILURES);
      }
    }
  }

  /**
   * Binds one {@link SpanEvent}'s fields to the 12 positional parameters of {@link #INSERT_SQL}.
   * <p>
   * The {@code stackTrace} is truncated to {@code 16000} characters to bound row size.
   *
   * @param stmt  the prepared insert statement
   * @param event the span event whose fields to bind
   * @throws SQLException if any {@code setX} call fails
   */
  private void bindEvent(PreparedStatement stmt, SpanEvent event) throws SQLException {
    stmt.setString(1, event.traceId());
    stmt.setString(2, event.spanId());
    stmt.setString(3, event.parentId());
    stmt.setString(4, event.spanName());
    stmt.setString(5, event.spanKind());
    stmt.setLong(6, event.startTimeMs());
    stmt.setLong(7, event.durationUs());
    stmt.setString(8, event.status());
    stmt.setString(9, event.exceptionClass());
    stmt.setString(10, event.exceptionMsg());
    stmt.setString(11, truncate(event.stackTrace(), 16000));
    stmt.setString(12, event.attributesJson());
  }

  /**
   * Best-effort final drain at shutdown: pulls and writes up to one {@link #BATCH_SIZE} batch
   * remaining in the sink, logging (not throwing) on any error.
   */
  private void flushRemaining() {
    try {
      List<SpanEvent> batch = SpanCollector.drain(BATCH_SIZE);
      if (!batch.isEmpty()) {
        writeBatch(batch);
      }
    } catch (Exception e) {
      AgentLog.warn("Flush on shutdown failed", e);
    }
  }

  /**
   * Rolls back the current transaction, logging (not throwing) on any {@link SQLException}. A no-op
   * when the connection is {@code null}.
   */
  private void rollbackQuietly() {
    try {
      if (conn != null) {
        conn.rollback();
      }
    } catch (SQLException e) {
      AgentLog.warn("Rollback failed", e);
    }
  }
}
