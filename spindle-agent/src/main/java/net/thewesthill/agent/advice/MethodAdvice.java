package net.thewesthill.agent.advice;

import net.bytebuddy.asm.Advice.Enter;
import net.bytebuddy.asm.Advice.OnMethodEnter;
import net.bytebuddy.asm.Advice.OnMethodExit;
import net.bytebuddy.asm.Advice.Origin;
import net.bytebuddy.asm.Advice.Thrown;

import net.thewesthill.agent.collector.SpanCollector;

/**
 * Generic ByteBuddy advice injected around arbitrary intercepted methods to record a span per
 * call.
 * <p>
 * The catch-all advice used by interceptor rules that do not need method-specific logic: on entry
 * it starts a span through {@link SpanCollector#onEnter} and returns a start-nanos token; on exit
 * it completes the span through {@link SpanCollector#onExit}. Both the span name and kind are
 * supplied by the runtime rather than hardcoded:
 * <ul>
 *   <li>{@code @Origin String origin} — the instrumented method's signature, used verbatim as the
 *       span name.</li>
 *   <li>{@code @SpanKind String kind} — the span kind, bound per rule by
 *       {@link net.thewesthill.agent.config.MatcherCompiler} via ByteBuddy's custom mapping
 *       ({@code Advice.withCustomMapping().bind(SpanKind.class, spanKind)}).</li>
 * </ul>
 * <p>
 * Both callbacks are wrapped in try/catch that report failures through
 * {@link SpanCollector#recordAdviceError} and swallow them — instrumentation must never throw into
 * the host application's business code.
 *
 * @author Jonah Fan
 * @since 1.0.0
 */
@SuppressWarnings("unused")
public final class MethodAdvice {

  /**
   * Private constructor; this class exposes only static advice methods.
   */
  private MethodAdvice() {
  }

  /**
   * Runs before the instrumented method: starts a span named after the method signature with the
   * rule-bound kind.
   *
   * @param origin the instrumented method's signature, bound via {@link Origin @Origin}
   * @param kind   the span kind, bound via {@link SpanKind @SpanKind} per interceptor rule
   * @return the enter token returned by {@link SpanCollector#onEnter} (a start-nanos value), or
   * {@code -1L} when a same-kind parent already covers this call (nested call suppressed), or
   * {@code 0L} if entry itself failed
   */
  @OnMethodEnter
  public static long enter(
      @Origin String origin, @SpanKind String kind) {
    try {
      return SpanCollector.onEnter(origin, kind);
    } catch (Throwable t) {
      SpanCollector.recordAdviceError();
      return 0L;
    }
  }

  /**
   * Runs after the instrumented method, including when it throws: completes the span.
   *
   * @param token  the value returned by {@link #enter}, bound via {@link Enter @Enter}
   * @param thrown the exception thrown by the instrumented method, or {@code null} on normal
   *               return, bound via {@link Thrown @Thrown}
   */
  @OnMethodExit(onThrowable = Throwable.class)
  public static void exit(
      @Enter long token,
      @Thrown Throwable thrown) {
    try {
      SpanCollector.onExit(token, thrown);
    } catch (Throwable t) {
      SpanCollector.recordAdviceError();
    }
  }
}
