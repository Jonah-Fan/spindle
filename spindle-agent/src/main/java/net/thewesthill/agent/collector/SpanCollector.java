package net.thewesthill.agent.collector;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import net.thewesthill.agent.context.TraceContext;
import net.thewesthill.agent.context.TraceContextHolder;
import net.thewesthill.agent.context.W3CPropagator;

public final class SpanCollector {

  private static final long SKIP_MARKER = -1L;
  private static final long ERROR_MARKER = 0L;
  private static final int NANOS_TO_MICROS = 1000;

  /**
   * Span kind marking an author-annotated boundary ({@code @Trace}). Never folded into a same-kind
   * parent — the author explicitly wants each nested layer recorded. All other kinds fold when
   * nested in a same-kind parent to suppress framework-internal delegation noise (e.g. Hibernate
   * {@code SessionImpl} routing through {@code SessionImplementor}).
   */
  private static final String KIND_CUSTOM = "CUSTOM";

  private static final SpanSink SINK = new InMemorySpanSink();
  private static final AtomicLong ADVICE_ERRORS = new AtomicLong();
  private static final ThreadLocal<ArrayDeque<SpanFrame>> STACK =
      ThreadLocal.withInitial(ArrayDeque::new);

  private SpanCollector() {
  }

  public static long adviceErrorCount() {
    return ADVICE_ERRORS.get();
  }

  public static void recordAdviceError() {
    ADVICE_ERRORS.incrementAndGet();
  }

  public static long onEnter(String spanName, String spanKind) {
    try {
      Deque<SpanFrame> frames = STACK.get();
      if (isNestedSameKind(frames, spanKind)) {
        return SKIP_MARKER;
      }

      long startNanos = System.nanoTime();
      long startMs = System.currentTimeMillis();
      TraceContext parent = TraceContextHolder.current();

      SpanContext ctx = SpanContext.create(parent, spanName, spanKind, startNanos, startMs);
      TraceContextHolder.set(ctx.traceContext());
      frames.push(ctx.frame());

      return startNanos;

    } catch (Throwable t) {
      recordAdviceError();
      return ERROR_MARKER;
    }
  }

  public static void onExit(long startNanos, Throwable thrown) {
    if (startNanos == SKIP_MARKER) {
      return;
    }
    try {
      Deque<SpanFrame> frames = STACK.get();
      SpanFrame frame = frames.poll();
      if (frame == null) {
        return;
      }

      restoreContext(frame.previousContext());

      SpanEvent event = frame.toEvent(
          durationMicros(frame.startNanos(), System.nanoTime()),
          thrown
      );
      SINK.offer(event);

    } catch (Throwable t) {
      recordAdviceError();
    }
  }

  public static List<SpanEvent> drain(int max) {
    return SINK.drain(max);
  }

  private static boolean isNestedSameKind(Deque<SpanFrame> frames, String spanKind) {
    if (KIND_CUSTOM.equals(spanKind)) {
      return false;
    }
    SpanFrame top = frames.peek();
    return top != null && top.spanKind().equals(spanKind);
  }

  private static long durationMicros(long startNanos, long endNanos) {
    return Math.max(0L, (endNanos - startNanos) / NANOS_TO_MICROS);
  }

  private static void restoreContext(TraceContext previous) {
    if (previous == null) {
      TraceContextHolder.clear();
    } else {
      TraceContextHolder.set(previous);
    }
  }

  private static String stackToString(Throwable t) {
    StringWriter sw = new StringWriter(1024);
    try (PrintWriter pw = new PrintWriter(sw)) {
      t.printStackTrace(pw);
    }
    return sw.toString();
  }

  private record SpanContext(SpanFrame frame, TraceContext traceContext) {

    public static SpanContext create(TraceContext parent, String spanName, String spanKind,
        long startNanos, long startMs) {
      if (parent == null) {
        String traceId = W3CPropagator.generateTraceId();
        String spanId = W3CPropagator.generateSpanId();
        return new SpanContext(
            new SpanFrame(startNanos, startMs, spanName, spanKind, traceId, spanId, null, null),
            TraceContext.newRoot(traceId, spanId)
        );
      }
      String spanId = W3CPropagator.generateSpanId();
      return new SpanContext(
          new SpanFrame(startNanos, startMs, spanName, spanKind,
              parent.traceId(), spanId, parent.spanId(), parent),
          parent.child(spanId)
      );
    }
  }

  private record SpanFrame(
      long startNanos,
      long startMs,
      String spanName,
      String spanKind,
      String traceId,
      String spanId,
      String parentId,
      TraceContext previousContext
  ) {

    SpanEvent toEvent(long durationUs, Throwable thrown) {
      return new SpanEvent(
          traceId, spanId, parentId,
          spanName, spanKind,
          startMs, durationUs,
          thrown == null ? "OK" : "ERROR",
          thrown == null ? null : thrown.getClass().getName(),
          thrown == null ? null : thrown.getMessage(),
          thrown == null ? null : stackToString(thrown),
          null
      );
    }
  }
}
