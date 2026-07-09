package net.thewesthill.agent.advice;

import net.bytebuddy.asm.Advice.Argument;
import net.bytebuddy.asm.Advice.OnMethodEnter;

import net.thewesthill.agent.context.TraceContextHolder;

/**
 * ByteBuddy advice injected at the entry of {@code ThreadPoolExecutor.execute(Runnable)} to
 * propagate the active trace context across the thread hand-off.
 * <p>
 * On entry, it captures the submitting thread's current {@link net.thewesthill.agent.context.TraceContext}
 * by wrapping the {@code Runnable} argument through {@link TraceContextHolder#wrap(Runnable)}. The
 * wrapper replays that context on the worker thread for the duration of the task, then restores the
 * worker's previous context — see {@link TraceContextHolder} for the full cross-thread flow. Because
 * {@code submit(Callable)} internally wraps its {@code Callable} in a {@code FutureTask} before
 * dispatching through {@code execute}, intercepting {@code execute} alone covers every submission
 * path.
 * <p>
 * <strong>Bootstrap-classloader constraint.</strong> This advice is inlined into the bootstrap JDK
 * class {@code ThreadPoolExecutor}, so at runtime it is resolved by the bootstrap classloader. It
 * may therefore only reference bootstrap-visible types: {@link TraceContextHolder} (pushed to the
 * bootstrap loader via {@code Boot-Class-Path}) and JDK types. It must <em>not</em> reference
 * agent-classloader types such as {@link net.thewesthill.agent.collector.SpanCollector} — doing so would
 * raise {@code NoClassDefFoundError}. For that reason the {@code catch} block below does not record
 * the error through {@code SpanCollector#recordAdviceError()}; it simply swallows the throwable so
 * the failure never propagates into the host application's task dispatch.
 *
 * @author Jonah Fan
 * @since 1.0.0
 */
@SuppressWarnings({"unused", "UnusedAssignment"})
public final class ThreadPoolAdvice {

  /**
   * Wraps the {@code execute(Runnable)} argument so the submitting thread's current trace context is
   * replayed on the worker thread.
   * <p>
   * The argument is declared with {@code readOnly = false}; the wrapped task must be assigned back to
   * {@code task} so ByteBuddy rewrites the call-site argument with the propagating wrapper. Any
   * throwable is swallowed — see the class documentation for the bootstrap-classloader constraint
   * that forbids delegating to {@link net.thewesthill.agent.collector.SpanCollector}.
   *
   * @param task the {@code Runnable} submitted to the executor, bound via
   *             {@link Argument @Argument(0)}; rewritten in place when a context is captured
   */
  @OnMethodEnter
  public static void enter(
      @Argument(value = 0, readOnly = false) Runnable task) {
    try {
      task = TraceContextHolder.wrap(task);
    } catch (Throwable t) {
      // NoClassDefFoundError would occur if SpanCollector (agent classloader) were referenced here,
      // since this advice is inlined into the bootstrap class ThreadPoolExecutor. Swallow the
      // throwable so instrumentation never disturbs the host application's task dispatch.
    }
  }
}
