package net.thewesthill.agent.server;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import net.thewesthill.agent.collector.SpanEvent;
import net.thewesthill.agent.log.AgentLog;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

/**
 * Embedded HTTP viewer that serves the recorded traces from the SQLite database.
 * <p>
 * Started by {@link net.thewesthill.agent.TraceAgent} when the viewer is enabled. It opens a
 * {@link TraceRepository} against the trace database, registers a fixed set of routes on a JDK
 * {@link HttpServer}, and serves both human-readable HTML pages and machine-readable JSON endpoints
 * over a small daemon thread pool.
 * <p>
 * Routes:
 * <ul>
 *   <li>{@code /} — HTML list of recent traces (see {@link #handleList}).</li>
 *   <li>{@code /trace/<id>} — HTML detail page for one trace (see {@link #handleDetail}).</li>
 *   <li>{@code /api/traces} — JSON list of recent traces (see {@link #handleApiTraces}).</li>
 *   <li>{@code /api/trace/<id>} — JSON detail for one trace (see {@link #handleApiTrace}).</li>
 *   <li>{@code /healthz} — liveness probe returning {@code "ok"}.</li>
 *   <li>{@code /favicon.ico} — empty 404 to keep browsers quiet.</li>
 * </ul>
 * <p>
 * Handler failures are logged and translated into a 500 response rather than propagated. Lifecycle:
 * construct ({@link #HttpViewerServer}), then {@link #start}, then {@link #close} on agent shutdown.
 *
 * @author Jonah Fan
 * @since 1.0.0
 */
public final class HttpViewerServer implements AutoCloseable {

  /**
   * Backing repository that queries the trace database.
   */
  private final TraceRepository repo;
  /**
   * TCP port the HTTP server listens on.
   */
  private final int port;
  /**
   * Pretty-printing Gson instance used to serialize JSON responses.
   */
  private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

  /**
   * The underlying JDK HTTP server; {@code null} until {@link #start} succeeds.
   */
  private HttpServer server;
  /**
   * Worker pool backing the HTTP server; {@code null} until {@link #start} succeeds.
   */
  private ExecutorService executor;

  /**
   * Creates a viewer targeting the given SQLite database file and port. Does not bind or open the
   * database yet — that happens in {@link #start}.
   *
   * @param dbPath the path to the SQLite database file to serve
   * @param port   the TCP port to listen on
   */
  public HttpViewerServer(String dbPath, int port) {
    this.repo = new TraceRepository(dbPath);
    this.port = port;
  }

  /**
   * Writes a complete HTTP response: sets the {@code Content-Type} header, sends the status line
   * and content length, then writes the UTF-8 encoded body. Any {@link IOException} while writing
   * the body is logged and swallowed — the headers have already been sent.
   *
   * @param ex          the exchange to respond on
   * @param status      the HTTP status code
   * @param contentType the {@code Content-Type} header value
   * @param body        the response body, encoded as UTF-8
   * @throws IOException if sending the response headers fails
   */
  private static void respond(HttpExchange ex, int status, String contentType, String body)
      throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().set("Content-Type", contentType);
    ex.sendResponseHeaders(status, bytes.length);
    try (OutputStream os = ex.getResponseBody()) {
      os.write(bytes);
    } catch (IOException e) {
      AgentLog.warn("failed to write response", e);
    }
  }

  /**
   * Parses the {@code limit} query parameter from the request URL, clamped to the range
   * {@code [1, 1000]}.
   * <p>
   * Looks for a {@code limit=<n>} pair in the query string. Returns the default of {@code 200} when
   * the parameter is absent or unparseable.
   *
   * @param ex the exchange whose request URI to inspect
   * @return the clamped limit, never less than {@code 1} nor greater than {@code 1000}
   */
  private static int parseLimit(HttpExchange ex) {
    String q = ex.getRequestURI().getQuery();
    if (q == null) {
      return 200;
    }
    for (String pair : q.split("&")) {
      String[] kv = pair.split("=", 2);
      if (kv.length == 2 && kv[0].equals("limit")) {
        try {
          return Math.max(1, Math.min(Integer.parseInt(kv[1]), 1000));
        } catch (NumberFormatException ignored) {
        }
      }
    }
    return 200;
  }

  /**
   * Starts the viewer: initializes the repository, creates and configures the HTTP server with its
   * routes and a 4-thread daemon pool, and begins listening.
   * <p>
   * A failure to bind or initialize is logged; the viewer is then effectively disabled but does not
   * throw into the agent.
   */
  public void start() {
    try {
      repo.init();

      server = HttpServer.create(new InetSocketAddress(port), 0);

      route("/", this::handleList);
      route("/trace/", this::handleDetail);
      route("/api/traces", this::handleApiTraces);
      route("/api/trace/", this::handleApiTrace);
      route("/healthz", (ex, p) -> respond(ex, 200, "text/plain", "ok"));
      route("/favicon.ico", (ex, p) -> respond(ex, 404, "text/plain", ""));

      executor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "spindle-viewer");
        t.setDaemon(true);
        return t;
      });
      server.setExecutor(executor);
      server.start();
      AgentLog.info("viewer listening on http://localhost:" + port + "/");
    } catch (IOException e) {
      AgentLog.error("viewer failed to start on port " + port, e);
    }
  }

  /**
   * Stops the HTTP server immediately and shuts down the worker pool, then closes the repository.
   * Safe to call even when {@link #start} failed (server/executor are simply {@code null}).
   */
  @Override
  public void close() {
    if (server != null) {
      server.stop(0);
    }
    if (executor != null) {
      executor.shutdownNow();
    }
    repo.close();
  }

  /**
   * Registers a route: a context rooted at {@code prefix} handled by {@code handler} via a
   * {@link RouteHandler}.
   *
   * @param prefix  the URL path prefix the route matches
   * @param handler the handler invoked for matching requests
   */
  private void route(String prefix, Route handler) {
    server.createContext(prefix, new RouteHandler(prefix, handler));
  }

  /**
   * Handles {@code /}: renders the HTML list of recent traces, limited by the request's
   * {@code limit} parameter.
   *
   * @param ex     the exchange
   * @param unused the path suffix after the prefix (ignored)
   * @throws IOException if writing the response fails
   */
  private void handleList(HttpExchange ex, String unused) throws IOException {
    List<TraceSummary> traces = repo.queryTraces(parseLimit(ex));
    respondHtml(ex, HtmlRenderer.listPage(traces));
  }

  /**
   * Handles {@code /trace/<id>}: renders the HTML detail page for the given trace, or a 400 when
   * the id is missing and a not-found page when the trace has no spans.
   *
   * @param ex      the exchange
   * @param traceId the trace id, taken from the path suffix after {@code /trace/}
   * @throws IOException if writing the response fails
   */
  private void handleDetail(HttpExchange ex, String traceId) throws IOException {
    if (traceId.isEmpty()) {
      respond(ex, 400, "text/plain", "missing traceId");
      return;
    }
    List<SpanEvent> spans = repo.querySpans(traceId);
    if (spans.isEmpty()) {
      respondHtml(ex, HtmlRenderer.notFound(traceId));
      return;
    }
    respondHtml(ex, HtmlRenderer.detailPage(traceId, spans));
  }

  /**
   * Handles {@code /api/traces}: returns the recent traces as a JSON array.
   *
   * @param ex     the exchange
   * @param unused the path suffix after the prefix (ignored)
   * @throws IOException if writing the response fails
   */
  private void handleApiTraces(HttpExchange ex, String unused) throws IOException {
    respondJson(ex, repo.queryTraces(parseLimit(ex)));
  }

  /**
   * Handles {@code /api/trace/<id>}: returns a JSON object with the trace id and its spans.
   *
   * @param ex      the exchange
   * @param traceId the trace id, taken from the path suffix after {@code /api/trace/}
   * @throws IOException if writing the response fails
   */
  private void handleApiTrace(HttpExchange ex, String traceId) throws IOException {
    List<SpanEvent> spans = repo.querySpans(traceId);
    respondJson(ex, Map.of("traceId", traceId, "spans", spans));
  }

  /**
   * Responds with the given HTML using HTTP 200 and a {@code text/html; charset=utf-8} content
   * type.
   *
   * @param ex   the exchange
   * @param html the HTML body
   * @throws IOException if writing the response fails
   */
  private void respondHtml(HttpExchange ex, String html) throws IOException {
    respond(ex, 200, "text/html; charset=utf-8", html);
  }

  /**
   * Responds with the given object as pretty-printed JSON using HTTP 200 and an
   * {@code application/json; charset=utf-8} content type.
   *
   * @param ex   the exchange
   * @param data the object to serialize via {@link #gson}
   * @throws IOException if writing the response fails
   */
  private void respondJson(HttpExchange ex, Object data) throws IOException {
    respond(ex, 200, "application/json; charset=utf-8", gson.toJson(data));
  }

  /**
   * A request handler invoked with the exchange and the path suffix following the route prefix.
   */
  @FunctionalInterface
  interface Route {

    /**
     * Handles a single request.
     *
     * @param ex    the exchange
     * @param param the portion of the request path after the route prefix
     * @throws IOException if handling the request fails
     */
    void handle(HttpExchange ex, String param) throws IOException;
  }

  /**
   * An {@link HttpHandler} that adapts a {@link Route} to the JDK HTTP server, applying the
   * root-route guard, error logging, and 500 fallback.
   */
  private record RouteHandler(String prefix, Route handler) implements HttpHandler {

    /**
     * Dispatches the request to the wrapped {@link Route}. Returns 404 for any non-root path when
     * the prefix is {@code "/"}, and translates any {@link IOException} or {@link RuntimeException}
     * from the handler into a logged 500 response.
     *
     * @param ex the exchange
     * @throws IOException if writing the response fails
     */
    @Override
    public void handle(HttpExchange ex) throws IOException {
      String path = ex.getRequestURI().getPath();
      if (prefix.equals("/") && !path.equals("/")) {
        respond(ex, 404, "text/plain", "not found");
        return;
      }
      try {
        handler.handle(ex, path.substring(prefix.length()));
      } catch (IOException | RuntimeException e) {
        AgentLog.warn("viewer handler failed: " + path, e);
        respond(ex, 500, "text/plain", "internal error");
      }
    }
  }
}
