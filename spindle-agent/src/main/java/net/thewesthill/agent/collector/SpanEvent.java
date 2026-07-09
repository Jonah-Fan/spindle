package net.thewesthill.agent.collector;

/**
 * Immutable record representing a single completed span in the trace pipeline.
 * <p>
 * The unit of data that flows through the collector: produced by {@link SpanCollector#onExit},
 * handed off to a {@link SpanSink} via {@link SpanSink#offer}, and later drained in batches by the
 * background reporter via {@link SpanSink#drain} for persistence.
 * <p>
 * A span captures one logical unit of work (e.g. one JDBC query) and carries the W3C trace
 * identifiers needed to assemble it into a trace tree:
 * <ul>
 *   <li>{@code traceId} / {@code spanId} / {@code parentId} link this span to its trace and parent</li>
 *   <li>{@code spanName} / {@code spanKind} describe what was done and by which subsystem</li>
 *   <li>{@code startTimeMs} / {@code durationUs} record when and how long it took</li>
 *   <li>{@code status} / {@code exceptionClass} / {@code exceptionMsg} / {@code stackTrace}
 *       capture success or failure</li>
 * </ul>
 * <p>
 * Instances are created only by {@link SpanCollector} and are otherwise immutable. The
 * {@code attributesJson} field is reserved for future structured attributes and is currently
 * always {@code null}.
 *
 * @author Jonah Fan
 * @since 1.0.0
 */
public record SpanEvent(
    String traceId,
    String spanId,
    String parentId,
    String spanName,
    String spanKind,
    long startTimeMs,
    long durationUs,
    String status,
    String exceptionClass,
    String exceptionMsg,
    String stackTrace,
    String attributesJson) {

  public boolean isError() {
    return !"OK".equals(status);
  }
}
