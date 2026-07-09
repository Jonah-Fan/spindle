package net.thewesthill.agent.collector;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory, bounded {@link SpanSink} backed by a {@link BlockingQueue}.
 * <p>
 * Producers (intercepted business threads) call {@link #offer}; the single reporter thread calls
 * {@link #drain}. This is the default sink used by {@link SpanCollector}.
 * <p>
 * Backpressure strategy:
 * <ul>
 *   <li>{@code offer} is non-blocking ({@link BlockingQueue#offer}). When the queue is full the
 *       span is <strong>dropped</strong> rather than blocking the business thread, and the drop is
 *       counted in {@link #dropped}.</li>
 *   <li>Dropping protects the application from an out-of-control producer, at the cost of trace
 *       completeness — acceptable for an APM agent whose primary contract is to never harm the
 *       host process.</li>
 * </ul>
 * <p>
 * Thread-safety relies entirely on the underlying {@link ArrayBlockingQueue}, which is safe for
 * concurrent multi-producer / single-consumer access.
 *
 * @author Jonah Fan
 * @since 1.0.0
 */
public final class InMemorySpanSink implements SpanSink {

  /**
   * Default queue capacity (16384). Holds completed spans awaiting the reporter's next batch
   * drain.
   */
  private static final int DEFAULT_CAPACITY = 16384;

  /**
   * The bounded queue buffering completed spans between producer and consumer.
   */
  private final BlockingQueue<SpanEvent> queue;

  /**
   * Running count of spans dropped because the queue was full at offer time.
   */
  private final AtomicLong dropped = new AtomicLong();

  /**
   * Creates a sink with the default capacity ({@value #DEFAULT_CAPACITY}).
   */
  public InMemorySpanSink() {
    this(DEFAULT_CAPACITY);
  }

  /**
   * Creates a sink with the specified capacity.
   *
   * @param capacity the maximum number of spans the queue can buffer before drops occur
   */
  public InMemorySpanSink(int capacity) {
    this.queue = new ArrayBlockingQueue<>(capacity);
  }

  /**
   * Enqueues a completed span non-blocking; drops (and counts) it if the queue is full.
   * <p>
   * Never blocks the calling business thread.
   *
   * @param span the completed span event to enqueue; never {@code null}
   * @return {@code true} if accepted, {@code false} if dropped due to a full queue
   */
  @Override
  public boolean offer(SpanEvent span) {
    if (queue.offer(span)) {
      return true;
    }
    dropped.incrementAndGet();
    return false;
  }

  /**
   * Drains up to {@code max} spans into a batch, non-blocking.
   * <p>
   * The returned spans are removed from the queue atomically via {@link BlockingQueue#drainTo}, so
   * they will not be re-delivered on the next drain.
   *
   * @param max the maximum number of spans to drain
   * @return a non-{@code null} list of drained span events ({@code size <= max})
   */
  @Override
  public List<SpanEvent> drain(int max) {
    List<SpanEvent> batch = new ArrayList<>(Math.min(max, 256));
    queue.drainTo(batch, max);
    return batch;
  }
}
