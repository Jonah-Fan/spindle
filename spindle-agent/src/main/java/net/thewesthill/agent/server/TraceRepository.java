package net.thewesthill.agent.server;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import net.thewesthill.agent.collector.SpanEvent;
import net.thewesthill.agent.log.AgentLog;

/**
 * Read-only JDBC accessor over the trace SQLite database backing the HTTP viewer.
 * <p>
 * Owns a single shared {@link Connection} (the viewer is single-process and low-traffic) and
 * exposes two parameterized queries: the trace-summary aggregation ({@link #queryTraces}) and the
 * per-trace span list ({@link #querySpans}). Both run through {@link #query}, which prepares the
 * statement, binds parameters via a {@link Mapper}, maps rows via a {@link Binder}, and swallows
 * any {@link SQLException} as a logged error returning an empty list.
 * <p>
 * Lifecycle: construct ({@link #TraceRepository}), {@link #init} to open the connection, then
 * {@link #close} on viewer shutdown.
 *
 * @author Jonah Fan
 * @since 1.0.0
 */
public class TraceRepository implements AutoCloseable {

  /**
   * Aggregate query producing one {@link TraceSummary} row per trace, newest first.
   */
  private static final String SQL_TRACES = """
      SELECT trace_id,
        (SELECT span_name FROM span WHERE trace_id = s.trace_id AND parent_id IS NULL LIMIT 1) AS root_name,
        (SELECT span_kind FROM span WHERE trace_id = s.trace_id AND parent_id IS NULL LIMIT 1) AS root_kind,
        MIN(start_time_ms) AS start_ms,
        MAX(start_time_ms + duration_us / 1000) AS end_ms,
        COUNT(*) AS span_count,
        SUM(CASE WHEN status='ERROR' THEN 1 ELSE 0 END) AS err_count
      FROM span s
      GROUP BY trace_id
      ORDER BY start_ms DESC
      LIMIT ?
      """;

  /**
   * Query returning all spans for a single trace, ordered by start time ascending.
   */
  private static final String SQL_SPANS = """
      SELECT trace_id, span_id, parent_id, span_name, span_kind,
             start_time_ms, duration_us, status,
             exception_class, exception_msg, stack_trace, attributes
      FROM span WHERE trace_id = ? ORDER BY start_time_ms ASC
      """;

  /**
   * Upper bound applied to any requested trace limit.
   */
  private static final int MAX_LIMIT = 1000;

  /**
   * Absolute path of the SQLite database file.
   */
  private final String dbPath;
  /**
   * The single shared connection; {@code null} until {@link #init} succeeds.
   */
  private Connection sharedConnection;

  /**
   * Creates a repository targeting the given SQLite database file. Does not connect yet.
   *
   * @param dbPath the path to the SQLite database file
   */
  public TraceRepository(String dbPath) {
    this.dbPath = dbPath;
  }

  /**
   * Loads the SQLite driver and opens the shared connection. A driver-not-found condition is logged
   * at debug (the JDBC {@code ServiceLoader} may still resolve it); a connection failure is logged
   * at warn, leaving {@link #sharedConnection} {@code null} so queries become no-ops.
   */
  public void init() {
    try {
      Class.forName("org.sqlite.JDBC");
    } catch (ClassNotFoundException e) {
      AgentLog.debug("sqlite driver not found by name: ", e.getMessage());
    }
    try {
      sharedConnection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
    } catch (SQLException e) {
      AgentLog.warn("failed to open viewer connection", e);
    }
  }

  /**
   * Returns up to {@code limit} trace summaries, newest first. The limit is clamped to
   * {@code [1, 1000]}. Returns an empty list if the connection is unavailable or the query fails.
   *
   * @param limit the maximum number of traces to return
   * @return the matching trace summaries, possibly empty
   */
  public List<TraceSummary> queryTraces(int limit) {
    int clamped = Math.max(1, Math.min(limit, MAX_LIMIT));
    return query(SQL_TRACES, this::mapTraceSummary, ps -> ps.setInt(1, clamped));
  }

  /**
   * Returns all spans belonging to the given trace, ordered by start time ascending. Returns an
   * empty list if the connection is unavailable, the query fails, or the trace has no spans.
   *
   * @param traceId the trace id to look up
   * @return the matching span events, possibly empty
   */
  public List<SpanEvent> querySpans(String traceId) {
    return query(SQL_SPANS, this::mapSpanEvent, ps -> ps.setString(1, traceId));
  }

  /**
   * Closes the shared connection, logging (not throwing) any {@link SQLException}. A no-op when the
   * connection was never opened.
   */
  @Override
  public void close() {
    if (sharedConnection != null) {
      try {
        sharedConnection.close();
      } catch (SQLException e) {
        AgentLog.warn("close connection failed", e);
      }
    }
  }

  /**
   * Runs a parameterized query, binding parameters via {@code binder} and mapping each row via
   * {@code mapper}. Any {@link SQLException} is logged and yields an empty result rather than
   * propagating.
   *
   * @param sql    the SQL to prepare
   * @param mapper the row mapper
   * @param binder the parameter binder
   * @param <T>    the row type
   * @return the mapped rows, possibly empty on error
   */
  private <T> List<T> query(String sql, Mapper<T> mapper, Binder binder) {
    List<T> result = new ArrayList<>();
    try (PreparedStatement ps = sharedConnection.prepareStatement(sql)) {
      binder.bind(ps);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          result.add(mapper.map(rs));
        }
      }
    } catch (SQLException e) {
      AgentLog.error("query failed", e);
    }
    return result;
  }

  /**
   * Maps a {@link TraceSummary} aggregate row to its record form.
   *
   * @param rs the result set, positioned on a row of {@link #SQL_TRACES}
   * @return the mapped trace summary
   * @throws SQLException if a column read fails
   */
  private TraceSummary mapTraceSummary(ResultSet rs) throws SQLException {
    return new TraceSummary(
        rs.getString("trace_id"),
        rs.getString("root_name"),
        rs.getString("root_kind"),
        rs.getLong("start_ms"),
        rs.getLong("end_ms"),
        rs.getInt("span_count"),
        rs.getInt("err_count")
    );
  }

  /**
   * Maps a span row to a {@link SpanEvent}.
   *
   * @param rs the result set, positioned on a row of {@link #SQL_SPANS}
   * @return the mapped span event
   * @throws SQLException if a column read fails
   */
  private SpanEvent mapSpanEvent(ResultSet rs) throws SQLException {
    return new SpanEvent(
        rs.getString("trace_id"),
        rs.getString("span_id"),
        rs.getString("parent_id"),
        rs.getString("span_name"),
        rs.getString("span_kind"),
        rs.getLong("start_time_ms"),
        rs.getLong("duration_us"),
        rs.getString("status"),
        rs.getString("exception_class"),
        rs.getString("exception_msg"),
        rs.getString("stack_trace"),
        rs.getString("attributes")
    );
  }

  /**
   * Binds parameters to a {@link PreparedStatement}.
   */
  @FunctionalInterface
  private interface Binder {

    /**
     * Binds positional parameters on the given statement.
     *
     * @param ps the statement to bind onto
     * @throws SQLException if a bind call fails
     */
    void bind(PreparedStatement ps) throws SQLException;
  }

  /**
   * Maps a {@link ResultSet} row to a value of type {@code T}.
   */
  @FunctionalInterface
  private interface Mapper<T> {

    /**
     * Maps the current row of {@code rs} to a value.
     *
     * @param rs the result set, positioned on a row
     * @return the mapped value
     * @throws SQLException if a column read fails
     */
    T map(ResultSet rs) throws SQLException;
  }
}
