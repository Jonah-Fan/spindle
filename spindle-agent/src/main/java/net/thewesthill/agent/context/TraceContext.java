package net.thewesthill.agent.context;

/**
 * Immutable value carrying the identifiers that locate a single span within its trace.
 * <p>
 * The propagation envelope shared across thread boundaries by {@link TraceContextHolder}. A
 * context carries three ids:
 * <ul>
 *   <li>{@code traceId} — the trace identifier, shared by every span in the same trace.</li>
 *   <li>{@code spanId} — the id of the current span.</li>
 *   <li>{@code parentId} — the id of the parent span, or {@code null} for a root span.</li>
 * </ul>
 * <p>
 * Instances are immutable; new contexts are derived via {@link #newRoot} (for the start of a trace)
 * or {@link #child(String)} (for a nested span), both of which preserve the trace id and link the
 * parent/child relationship.
 *
 * @param traceId  the trace identifier
 * @param spanId   the current span identifier
 * @param parentId the parent span identifier, or {@code null} for a root span
 *
 * @author Jonah Fan
 * @since 1.0.0
 */
public record TraceContext(String traceId, String spanId, String parentId) {

  /**
   * Creates a root context: a new trace with the given ids and no parent.
   *
   * @param traceId the trace identifier
   * @param spanId  the root span identifier
   * @return a root context whose {@link #parentId} is {@code null}
   */
  public static TraceContext newRoot(String traceId, String spanId) {
    return new TraceContext(traceId, spanId, null);
  }

  /**
   * Derives a child context that continues this trace: same {@code traceId}, the child's span id,
   * and this context's {@code spanId} as the child's {@code parentId}.
   *
   * @param childSpanId the span id of the child
   * @return the child context
   */
  public TraceContext child(String childSpanId) {
    return new TraceContext(this.traceId, childSpanId, this.spanId);
  }
}
