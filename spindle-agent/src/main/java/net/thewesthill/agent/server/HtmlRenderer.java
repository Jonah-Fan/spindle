package net.thewesthill.agent.server;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

import net.thewesthill.agent.collector.SpanEvent;

/**
 * Stateless renderer that turns trace data into HTML pages via {@link HtmlBuilder}.
 * <p>
 * Produces three-page kinds: the trace list ({@link #listPage}), the per-trace detail with a
 * recursive span tree ({@link #detailPage}), and a not-found page ({@link #notFound}). All
 * user-supplied text is HTML-escaped through {@link #escape} before being emitted.
 *
 * @author Jonah Fan
 * @since 1.0.0
 */
public final class HtmlRenderer {

  /**
   * Timestamp formatter for span start times, ISO local date-time in the system zone.
   */
  private static final DateTimeFormatter TIME_FMT = DateTimeFormatter
      .ISO_LOCAL_DATE_TIME.withZone(ZoneId.systemDefault());

  /**
   * Private constructor; this class exposes only static methods.
   */
  private HtmlRenderer() {
  }

  /**
   * Renders the trace-list page as a styled HTML table, one row per trace, with a link to each
   * trace's detail page and a JSON link.
   *
   * @param traces the traces to list
   * @return the complete HTML document
   */
  public static String listPage(List<TraceSummary> traces) {
    return new HtmlBuilder()
        .title("Spindle")
        .style("""
            body{font-family:system-ui,sans-serif;margin:20px}
            table{border-collapse:collapse;width:100%}
            th,td{border:1px solid #e0e0e0;padding:6px 10px;text-align:left;font-size:13px}
            th{background:#f6f8fa}
            .ok{color:#1a7f37} .err{color:#d1242f;font-weight:600}
            a{color:#0969da;text-decoration:none}
            .meta{color:#57606a;margin:8px 0}
            """)
        .body()
        .h1("Traces")
        .tag("div", "meta", traces.size() + " traces · <a href=\"/api/traces\">JSON</a>")
        .table()
        .thead("Trace ID", "Root", "Kind", "Start", "Duration", "Spans", "Status")
        .tbody(rows -> {
          for (TraceSummary t : traces) {
            rows.tr()
                .tdLink("/trace/" + t.traceId(), shorten(t.traceId(), 16))
                .td(t.rootName() != null ? t.rootName() : "")
                .td(t.rootKind() != null ? t.rootKind() : "")
                .td(fmtTime(t.startMs()))
                .td(t.durationMs() + " ms")
                .td(String.valueOf(t.spanCount()))
                .tdRaw(t.hasError()
                    ? "<span class=\"err\">ERROR (" + t.errCount() + ")</span>"
                    : "<span class=\"ok\">OK</span>")
                .endTr();
          }
        })
        .endTable()
        .endBody()
        .build();
  }

  /**
   * Renders the per-trace detail page: builds a {@link SpanTree} from the spans and renders each
   * root span (and its descendants recursively) as a nested list.
   *
   * @param traceId the trace id being viewed
   * @param spans   the trace's spans
   * @return the complete HTML document
   */
  public static String detailPage(String traceId, List<SpanEvent> spans) {
    SpanTree tree = SpanTree.build(spans);

    return new HtmlBuilder()
        .title("Trace " + shorten(traceId, 8))
        .style("""
            ul{list-style:none;padding-left:20px;border-left:1px solid #e0e0e0}
            li{padding:6px 0 6px 12px;position:relative}
            .name{font-weight:600} .kind{color:#888;font-size:12px}
            .dur{color:#0969da} .err{color:#d1242f}
            """)
        .body()
        .link("/", "back", "← Back to list")
        .h1("Trace: " + traceId)
        .tag("div", "meta",
            spans.size() + " spans · <a href=\"/api/trace/" + traceId + "\">JSON</a>")
        .tag("ul", null, null)
        .each(tree.roots(), (b, span) -> renderSpan(b, span, tree, 0))
        .endTag() // ul
        .endBody()
        .build();
  }

  /**
   * Renders a not-found page for a missing trace id, with a link back to the list.
   *
   * @param traceId the trace id that was not found
   * @return the complete HTML document
   */
  public static String notFound(String traceId) {
    return new HtmlBuilder()
        .title("Not Found")
        .body()
        .tag("p", null, "Trace <code>" + escape(traceId) + "</code> not found.")
        .link("/", null, "← Back to list")
        .endBody()
        .build();
  }

  /**
   * Recursively renders one span as an {@code <li>}: its name, kind, duration, error badge, and any
   * exception/stack trace, followed by a nested {@code <ul>} of its children.
   *
   * @param b     the builder to append to
   * @param span  the span to render
   * @param tree  the span tree used to look up children
   * @param depth the current nesting depth (root spans start at 0)
   */
  private static void renderSpan(HtmlBuilder b, SpanEvent span, SpanTree tree, int depth) {
    b.tag("li", null, null)
        .tag("span", "name", escape(span.spanName()));

    if (span.spanKind() != null) {
      b.tag("span", "kind", escape(span.spanKind()));
    }
    b.tag("span", "dur", fmtDuration(span.durationUs()));

    if (span.isError()) {
      b.tag("span", "err", " ERROR");
    }
    if (span.exceptionClass() != null) {
      b.tag("div", null, escape(span.exceptionClass()) + ": " + escape(span.exceptionMsg()));
      if (span.stackTrace() != null) {
        b.tag("pre", null, escape(span.stackTrace()));
      }
    }

    List<SpanEvent> children = tree.childrenOf(span.spanId());
    if (!children.isEmpty()) {
      b.tag("ul", null, null);
      for (SpanEvent child : children) {
        renderSpan(b, child, tree, depth + 1);
      }
      b.endTag(); // ul
    }
    b.endTag(); // li
  }

  /**
   * HTML-escapes a string for safe emission into the document, returning the empty string for
   * {@code null}. Escapes {@code &}, {@code <}, {@code >}, and {@code "}.
   *
   * @param s the string to escape, or {@code null}
   * @return the escaped string, or {@code ""} if {@code s} was {@code null}
   */
  private static String escape(String s) {
    if (s == null) {
      return "";
    }
    return s.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;");
  }

  /**
   * Shortens a string to {@code max} characters, appending an ellipsis when truncated, and returns
   * the empty string for {@code null}.
   *
   * @param s   the string to shorten, or {@code null}
   * @param max the maximum number of leading characters to keep
   * @return the (possibly truncated) string, or {@code ""} if {@code s} was {@code null}
   */
  private static String shorten(String s, int max) {
    return s != null && s.length() > max ? s.substring(0, max) + "..." : Objects.toString(s, "");
  }

  /**
   * Formats an epoch-millis timestamp as {@code ISO local date-time} with the {@code T} separator
   * replaced by a space, in the system zone.
   *
   * @param ms the epoch-millis timestamp
   * @return the formatted timestamp string
   */
  private static String fmtTime(long ms) {
    return TIME_FMT.format(Instant.ofEpochMilli(ms)).replace('T', ' ');
  }

  /**
   * Formats a microsecond duration with an adaptive unit: {@code μs} below 1 ms, {@code ms} below 1
   * s, otherwise {@code s}, always with two decimals where applicable.
   *
   * @param us the duration in microseconds
   * @return the formatted duration string
   */
  private static String fmtDuration(long us) {
    if (us < 1000) {
      return us + " μs";
    }
    if (us < 1_000_000) {
      return String.format("%.2f ms", us / 1000.0);
    }
    return String.format("%.2f s", us / 1_000_000.0);
  }
}