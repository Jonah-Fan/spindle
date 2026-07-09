package net.thewesthill.agent.context;

import java.util.concurrent.Callable;

/**
 * Per-thread carrier for the <em>current</em> {@link TraceContext} and the bridge that propagates it
 * across thread boundaries.
 * <p>
 * The active context lives in a {@link ThreadLocal} ({@link #CURRENT}), so by default it is bound to
 * the thread that created it and does <strong>not</strong> follow work submitted to another thread.
 * For distributed tracing to span thread hand-offs (e.g. {@code ThreadPoolExecutor}), the producing
 * thread must explicitly snapshot its current context and the consuming thread must replay it; that
 * is exactly what {@link #wrap(Runnable)} and {@link #wrap(Callable)} do.
 * <p>
 * Cross-thread flow:
 * <ol>
 *   <li>The submitting thread calls {@link #wrap} on a task; the current context is captured at that
 *       moment into the returned wrapper.</li>
 *   <li>The wrapper, when run on the worker thread, installs the snapshot as the worker's current
 *       context, runs the task, and in a {@code finally} restores the worker's previous context (or
 *       clears it if it had none).</li>
 * </ol>
 * The restore step is essential for pooled threads: without it the propagated context would leak into
 * the worker's next, unrelated task. {@link #wrap} is a no-op returning the original task unchanged
 * when the submitting thread has no current context, so there is no propagation overhead outside a
 * trace.
 * <p>
 * <strong>Class-loading note.</strong> This class (and {@link TraceContext}) is packaged in a
 * separate zero-dependency {@code spindle-ctx.jar} and pushed to the bootstrap classloader via
 * {@code Boot-Class-Path}. This is required because the advice that triggers propagation —
 * {@link net.thewesthill.agent.advice.ThreadPoolAdvice} — is inlined into the bootstrap JDK class
 * {@code ThreadPoolExecutor}, whose classloader can only see bootstrap types. The main shaded jar
 * excludes these classes so a single {@code Class} instance is shared; otherwise two
 * {@code TraceContextHolder} classes would each hold their own {@code ThreadLocal} and propagation
 * would silently fail to bridge the producer and consumer.
 *
 * @author Jonah Fan
 * @since 1.0.0
 */
public final class TraceContextHolder {

  /** The per-thread current context. {@code null} when no span is active on the thread. */
  private static final ThreadLocal<TraceContext> CURRENT = new ThreadLocal<>();

  /** Private constructor; this class exposes only static methods. */
  private TraceContextHolder() {
  }

  /**
   * Returns the context active on the calling thread, or {@code null} if no span is currently
   * active.
   *
   * @return the current {@link TraceContext}, or {@code null}
   */
  public static TraceContext current() {
    return CURRENT.get();
  }

  /**
   * Installs {@code ctx} as the current context on the calling thread, replacing any previous
   * context. Callers are responsible for restoring the prior context (or calling {@link #clear})
   * when the span completes.
   *
   * @param ctx the context to make current, or {@code null}
   */
  public static void set(TraceContext ctx) {
    CURRENT.set(ctx);
  }

  /**
   * Removes the current context from the calling thread, leaving no active span. Idempotent.
   */
  public static void clear() {
    CURRENT.remove();
  }

  /**
   * Wraps a {@link Runnable} so the calling thread's current context is replayed on the thread that
   * runs the task.
   * <p>
   * Captures the context now and returns a {@link TraceRunnable} that installs it (restoring the
   * runner's previous context afterward). Returns the original {@code task} unchanged when the
   * calling thread has no current context, and {@code null} when {@code task} is {@code null}.
   *
   * @param task the task to wrap, or {@code null}
   * @return a context-propagating wrapper, the original task if nothing to propagate, or
   *         {@code null} if {@code task} was {@code null}
   */
  public static Runnable wrap(Runnable task) {
    if (task == null) {
      return null;
    }
    TraceContext snapshot = CURRENT.get();
    if (snapshot == null) {
      return task;
    }
    return new TraceRunnable(task, snapshot);
  }

  /**
   * Wraps a {@link Callable} so the calling thread's current context is replayed on the thread that
   * runs the task.
   * <p>
   * Captures the context now and returns a {@link TraceCallable} that installs it (restoring the
   * runner's previous context afterward). Returns the original {@code task} unchanged when the
   * calling thread has no current context, and {@code null} when {@code task} is {@code null}.
   *
   * @param task the task to wrap, or {@code null}
   * @param <V>  the callable's result type
   * @return a context-propagating wrapper, the original task if nothing to propagate, or
   *         {@code null} if {@code task} was {@code null}
   */
  @SuppressWarnings("unused")
  public static <V> Callable<V> wrap(Callable<V> task) {
    if (task == null) {
      return null;
    }
    TraceContext snapshot = CURRENT.get();
    if (snapshot == null) {
      return task;
    }
    return new TraceCallable<>(task, snapshot);
  }

  /**
   * A {@link Runnable} that propagates a captured {@link TraceContext} to its executing thread.
   * <p>
   * On run, it saves the runner's current context, installs the captured {@code snapshot}, runs the
   * delegate, and in a {@code finally} restores the saved context — or clears it if the runner had
   * none — so the propagated context never outlives the task on a pooled thread.
   */
  public static final class TraceRunnable implements Runnable {

    /** The wrapped task. */
    private final Runnable delegate;
    /** The context captured on the submitting thread, replayed on the runner. */
    private final TraceContext snapshot;

    /**
     * Creates a propagating wrapper.
     *
     * @param delegate the task to delegate to
     * @param snapshot the context captured on the submitting thread
     */
    TraceRunnable(Runnable delegate, TraceContext snapshot) {
      this.delegate = delegate;
      this.snapshot = snapshot;
    }

    /**
     * Installs {@link #snapshot} as the current context, runs the delegate, then restores the
     * runner's previous context (or clears it) regardless of how the delegate completes.
     */
    @Override
    public void run() {
      TraceContext previous = CURRENT.get();
      CURRENT.set(snapshot);
      try {
        delegate.run();
      } finally {
        if (previous == null) {
          CURRENT.remove();
        } else {
          CURRENT.set(previous);
        }
      }
    }
  }

  /**
   * A {@link Callable} that propagates a captured {@link TraceContext} to its executing thread.
   * <p>
   * On call, it saves the runner's current context, installs the captured {@code snapshot}, calls the
   * delegate, and in a {@code finally} restores the saved context — or clears it if the runner had
   * none — so the propagated context never outlives the task on a pooled thread.
   *
   * @param <V> the callable's result type
   */
  public static final class TraceCallable<V> implements Callable<V> {

    /** The wrapped task. */
    private final Callable<V> delegate;
    /** The context captured on the submitting thread, replayed on the runner. */
    private final TraceContext snapshot;

    /**
     * Creates a propagating wrapper.
     *
     * @param delegate the task to delegate to
     * @param snapshot the context captured on the submitting thread
     */
    public TraceCallable(Callable<V> delegate, TraceContext snapshot) {
      this.delegate = delegate;
      this.snapshot = snapshot;
    }

    /**
     * Installs {@link #snapshot} as the current context, calls the delegate, then restores the
     * runner's previous context (or clears it) regardless of how the delegate completes.
     *
     * @return the delegate's result
     * @throws Exception if the delegate throws
     */
    @Override
    public V call() throws Exception {
      TraceContext previous = CURRENT.get();
      CURRENT.set(snapshot);
      try {
        return delegate.call();
      } finally {
        if (previous == null) {
          CURRENT.remove();
        } else {
          CURRENT.set(previous);
        }
      }
    }
  }
}
