package net.thewesthill.agent.collector;

import java.util.List;

/**
 * Span sink: the hand-off boundary between synchronous span collection and asynchronous reporting.
 * <p>
 * Spans are produced on intercepted business threads by {@link SpanCollector#onExit}, which calls
 * {@link #offer}. They are consumed by the background reporter thread, which calls {@link #drain}
 * to pull a batch and persist it to storage (e.g. SQLite).
 * <p>
 * The sink decouples the two sides so that:
 * <ul>
 *   <li>Intercepted business threads never block on I/O — {@code offer} must be non-blocking and
 *       tolerate a full queue (e.g. by dropping) rather than stalling the application.</li>
 *   <li>The reporter thread controls its own batching cadence and is the only writer to storage.</li>
 * </ul>
 * <p>
 * Implementations must be thread-safe: {@code offer} is invoked concurrently from many business
 * threads, while {@code drain} is invoked from a single reporter thread.
 *
 * @author Jonah Fan
 * @since 1.0.0
 */
public interface SpanSink {

  /**
   * Enqueues a completed span event, non-blocking.
   * <p>
   * Called from intercepted business threads. Must never block the caller; if the internal buffer
   * is full the implementation may drop the span (and is expected to count it as dropped).
   *
   * @param span the completed span event to enqueue; never {@code null}
   * @return {@code true} if the span was accepted, {@code false} if it was dropped (e.g. queue
   * full)
   */
  boolean offer(SpanEvent span);

  /**
   * Drains up to {@code max} queued span events into a batch, non-blocking.
   * <p>
   * Called from the single reporter thread. Removes the returned spans from the sink so they are
   * not delivered twice. Returns an empty list when the sink is empty.
   *
   * @param max the maximum number of spans to drain in this batch
   * @return a non-{@code null} list of drained span events ({@code size <= max})
   */
  List<SpanEvent> drain(int max);
}
