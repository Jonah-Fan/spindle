package net.thewesthill.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.thewesthill.agent.collector.SpanCollector;
import net.thewesthill.agent.reporter.SqliteReporter;
import net.thewesthill.agent.server.HttpViewerServer;

/**
 * Integration smoke test for the reporter -> SQLite -> viewer -> HTTP pipeline, exercised entirely
 * within a single JVM (no ByteBuddy attachment, no separate process).
 * <p>
 * A span is produced via the real {@link SpanCollector#onEnter}/{@link SpanCollector#onExit} path so
 * it flows through the agent's global {@link net.thewesthill.agent.collector.InMemorySpanSink}, is
 * drained and persisted by {@link SqliteReporter}, and is then read back through the embedded
 * {@link HttpViewerServer}'s JSON endpoint. This proves the storage + viewer chain end-to-end — the
 * same chain the {@code -javaagent} path relies on — without the timing flakiness of spawning a JVM.
 */
class TracePipelineSmokeTest {

  /** A free-ish high port for the viewer; collisions are unlikely in CI and the test fails fast if bound. */
  private static final int VIEWER_PORT = 48787;
  private static final String SPAN_NAME = "smoke-test-span";
  private static final String SPAN_KIND = "CUSTOM";

  @TempDir
  Path tmp;

  private SqliteReporter reporter;
  private HttpViewerServer viewer;

  @BeforeEach
  void setUp() {
    Path dbPath = tmp.resolve("trace.db");

    // The agent's AgentLog is a static singleton whose init() installs a rotating FileHandler that
    // keeps a lock on a log file; we deliberately do NOT call init() here, so no file is created
    // inside the @TempDir and JUnit can clean it up. The logger falls back to console output.
    reporter = new SqliteReporter(dbPath.toString());
    reporter.start();

    viewer = new HttpViewerServer(dbPath.toString(), VIEWER_PORT);
    viewer.start();
  }

  @AfterEach
  void tearDown() throws Exception {
    if (viewer != null) {
      viewer.close();
    }
    if (reporter != null) {
      reporter.close();
    }
  }

  @Test
  @DisplayName("a span produced via SpanCollector is persisted and served by the viewer's /api/traces")
  void spanFlowsThroughPipeline() throws Exception {
    // Produce one root span through the real collector path; onExit hands it to the global sink.
    long start = SpanCollector.onEnter(SPAN_NAME, SPAN_KIND);
    SpanCollector.onExit(start, null);

    // The reporter drains every ~100ms; wait until the trace shows up in the viewer's JSON.
    String body = waitForTraceList(Duration.ofSeconds(10));
    assertThat(body)
        .as("/api/traces should include the smoke-test span")
        .contains(SPAN_NAME);
  }

  /**
   * Polls {@code /api/traces} until it returns a body containing the produced span, or throws after
   * the timeout. Without this wait the test would race the reporter's async drain loop.
   */
  @SuppressWarnings("BusyWait")
  private String waitForTraceList(Duration timeout) throws IOException, InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    // HttpClient is not AutoCloseable on Java 17 (it becomes so in 21), so no try-with-resources;
    // it holds no resources that require explicit release.
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    URI uri = URI.create("http://localhost:" + VIEWER_PORT + "/api/traces");
    while (System.nanoTime() < deadline) {
      HttpResponse<String> resp = client.send(
          HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(2)).GET().build(),
          HttpResponse.BodyHandlers.ofString());
      if (resp.statusCode() == 200 && resp.body().contains(SPAN_NAME)) {
        return resp.body();
      }
      Thread.sleep(100);
    }
    throw new AssertionError("span '" + SPAN_NAME + "' never appeared in /api/traces within " + timeout);
  }
}
