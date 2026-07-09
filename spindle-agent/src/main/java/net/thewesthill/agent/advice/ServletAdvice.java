package net.thewesthill.agent.advice;

import net.bytebuddy.asm.Advice.Argument;
import net.bytebuddy.asm.Advice.Enter;
import net.bytebuddy.asm.Advice.OnMethodEnter;
import net.bytebuddy.asm.Advice.OnMethodExit;
import net.bytebuddy.asm.Advice.Origin;
import net.bytebuddy.asm.Advice.Thrown;

import net.thewesthill.agent.collector.SpanCollector;
import net.thewesthill.agent.context.TraceContext;
import net.thewesthill.agent.context.TraceContextHolder;
import net.thewesthill.agent.context.W3CPropagator;

/**
 * ByteBuddy advice injected around the servlet entry points ({@code service}, {@code doDispatch})
 * on {@code *HttpServlet} subclasses, supporting both {@code javax.servlet} and
 * {@code jakarta.servlet}.
 * <p>
 * Reflective access — the servlet API is resolved by reflection ({@link #safeInvoke}) rather than
 * referenced directly, so the advice loads even when the servlet API is absent from the classpath.
 * The HTTP method, request URI, and {@code traceparent} header are read this way.
 * <p>
 * Entry responsibilities:
 * <ol>
 *   <li>Inbound W3C propagation: when an incoming {@code traceparent} header is present and the
 *       thread has no current context, parse it via {@link W3CPropagator#parseChild} and install it
 *       as the parent {@link TraceContext} through {@link TraceContextHolder#set}.</li>
 *   <li>Root span creation: start a span named {@code "HTTP <method> <uri>"} of kind {@code HTTP}
 *       via {@link SpanCollector#onEnter}.</li>
 * </ol>
 * Both callbacks are wrapped in try/catch that report failures via
 * {@link SpanCollector#recordAdviceError} and swallow them — instrumentation must never throw into
 * the host application's business code.
 *
 * @author Jonah Fan
 * @since 1.0.0
 */
@SuppressWarnings({"unused", "SameParameterValue"})
public final class ServletAdvice {

  /** Name of the W3C trace-context request header carrying the inbound traceparent. */
  private static final String TRACE_PARENT_HEADER = "traceparent";

  /**
   * Runs before the instrumented servlet method: extracts the HTTP method, URI, and traceparent
   * header from the request, propagates the inbound context, then starts the HTTP root span.
   * <p>
   * When the request argument is {@code null}, a degenerate {@code "HTTP"} span is still started so
   * the call is recorded. The {@code traceparent} is only applied when the thread has no current
   * context, preserving an already-active trace.
   *
   * @param origin the instrumented method's signature, bound via {@link Origin @Origin}
   * @param req    the servlet request argument, bound via {@link Argument @Argument(0)}; may be
   *               {@code null}
   * @return the enter token returned by {@link SpanCollector#onEnter} (a start-nanos value), or
   *         {@code -1L} when a same-kind parent already covers this call (nested call suppressed),
   *         or {@code 0L} if entry itself failed
   */
  @OnMethodEnter
  public static long enter(@Origin String origin, @Argument(0) Object req) {
    try {
      if (req == null) {
        return SpanCollector.onEnter("HTTP", "HTTP");
      }

      String method = safeInvoke(req, "getMethod");
      String uri = safeInvoke(req, "getRequestURI");
      String traceParent = safeInvoke(req, "getHeader", TRACE_PARENT_HEADER);

      if (traceParent != null && !traceParent.isEmpty()) {
        parseAndSetTraceContext(traceParent);
      }

      String spanName = "HTTP " + orEmpty(method) + " " + orEmpty(uri);
      return SpanCollector.onEnter(spanName, "HTTP");

    } catch (Throwable t) {
      SpanCollector.recordAdviceError();
      return 0L;
    }
  }

  /**
   * Runs after the instrumented servlet method, including when it throws: completes the span.
   *
   * @param token  the value returned by {@link #enter}, bound via {@link Enter @Enter}
   * @param thrown the exception thrown by the instrumented method, or {@code null} on normal return,
   *               bound via {@link Thrown @Thrown}
   */
  @OnMethodExit(onThrowable = Throwable.class)
  public static void exit(@Enter long token, @Thrown Throwable thrown) {
    try {
      SpanCollector.onExit(token, thrown);
    } catch (Throwable t) {
      SpanCollector.recordAdviceError();
    }
  }

  /**
   * Applies an inbound traceparent as the current context, but only when the thread has none yet.
   * <p>
   * Parses {@code traceParent} via {@link W3CPropagator#parseChild}; a parse failure (returns
   * {@code null}) is silently ignored. The "no current context" guard keeps an already-active trace
   * from being overwritten by an inbound header.
   *
   * @param traceParent the raw {@code traceparent} header value, non-null and non-empty
   */
  private static void parseAndSetTraceContext(String traceParent) {
    if (TraceContextHolder.current() != null) {
      return;
    }
    TraceContext parent = W3CPropagator.parseChild(traceParent);
    if (parent != null) {
      TraceContextHolder.set(parent);
    }
  }

  /**
   * Returns {@code s} or the empty string when it is {@code null}, used to safely build span names.
   *
   * @param s the string, or {@code null}
   * @return {@code s}, or {@code ""}
   */
  private static String orEmpty(String s) {
    return s == null ? "" : s;
  }

  /**
   * Reflectively invokes a no-arg method on {@code target} returning a {@code String}, reporting
   * any failure via {@link SpanCollector#recordAdviceError} and returning {@code null} so a missing
   * or failing accessor degrades gracefully rather than throwing.
   *
   * @param target     the object to invoke on
   * @param methodName the no-arg method to call
   * @return the method's {@code String} result, or {@code null} on failure
   */
  private static String safeInvoke(Object target, String methodName) {
    try {
      return (String) target.getClass().getMethod(methodName).invoke(target);
    } catch (Throwable t) {
      SpanCollector.recordAdviceError();
      return null;
    }
  }

  /**
   * Reflectively invokes a single-arg (String) method on {@code target} returning a {@code String},
   * reporting any failure via {@link SpanCollector#recordAdviceError} and returning {@code null} so
   * a missing or failing accessor degrades gracefully rather than throwing.
   *
   * @param target     the object to invoke on
   * @param methodName the single-arg method to call
   * @param arg        the {@code String} argument to pass
   * @return the method's {@code String} result, or {@code null} on failure
   */
  private static String safeInvoke(Object target, String methodName, String arg) {
    try {
      return (String) target.getClass().getMethod(methodName, String.class).invoke(target, arg);
    } catch (Throwable t) {
      SpanCollector.recordAdviceError();
      return null;
    }
  }
}
