package net.thewesthill.agent.server;

/**
 * Aggregate summary of one trace, produced by {@link TraceRepository#queryTraces}.
 * <p>
 * One row per trace id, capturing the root span's name and kind, the trace's overall time bounds,
 * and span/error counts. The convenience accessors {@link #durationMs()} and {@link #hasError()}
 * derive derived values from the raw fields.
 *
 * @param traceId   the trace identifier
 * @param rootName  the name of the root span (parent id {@code null}), or {@code null} if none
 * @param rootKind  the kind of the root span, or {@code null} if none
 * @param startMs   the earliest span start time, in epoch milliseconds
 * @param endMs     the latest span end time (start + duration), in epoch milliseconds
 * @param spanCount the total number of spans in the trace
 * @param errCount  the number of spans with {@code ERROR} status
 * @author Jonah Fan
 * @since 1.0.0
 */
public record TraceSummary(
    String traceId,
    String rootName,
    String rootKind,
    long startMs,
    long endMs,
    int spanCount,
    int errCount
) {

  /**
   * Returns the trace's wall-clock duration in milliseconds.
   *
   * @return {@code endMs - startMs}
   */
  long durationMs() {
    return endMs - startMs;
  }

  /**
   * Indicates whether the trace contains any error spans.
   *
   * @return {@code true} if {@code errCount > 0}
   */
  boolean hasError() {
    return errCount > 0;
  }
}
