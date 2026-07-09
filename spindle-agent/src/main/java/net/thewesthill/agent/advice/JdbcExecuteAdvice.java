package net.thewesthill.agent.advice;

import net.bytebuddy.asm.Advice.Enter;
import net.bytebuddy.asm.Advice.OnMethodEnter;
import net.bytebuddy.asm.Advice.OnMethodExit;
import net.bytebuddy.asm.Advice.Thrown;

import net.thewesthill.agent.collector.JdbcSqlHolder;
import net.thewesthill.agent.collector.SpanCollector;

/**
 * ByteBuddy advice injected around the JDBC execute-family methods ({@code execute},
 * {@code executeQuery}, {@code executeUpdate}, {@code executeBatch}, {@code executeLargeUpdate}) on
 * {@link java.sql.Connection} and {@link java.sql.PreparedStatement}.
 * <p>
 * On entry, it names a span from the SQL stashed by {@link JdbcPrepareAdvice} via
 * {@link JdbcSqlHolder#get} — falling back to {@code "JDBC.execute"} when no SQL is available —
 * then starts the span through {@link SpanCollector#onEnter}. On exit, it completes the span via
 * {@link SpanCollector#onExit} and pops the stashed SQL via {@link JdbcSqlHolder#clear} so the
 * per-thread stack stays balanced with the prepare/execute nesting.
 * <p>
 * Registered for the {@code jdbc} interceptor in {@code defaults-interceptors.yml} against the
 * execute-family method names.
 * <p>
 * Both callbacks are wrapped in try/catch that report failures via
 * {@link SpanCollector#recordAdviceError} and swallow them — instrumentation must never throw into
 * the host application's business code.
 *
 * @author Jonah Fan
 * @since 1.0.0
 */
@SuppressWarnings("unused")
public final class JdbcExecuteAdvice {

  /**
   * Runs before the instrumented execute-family call: resolves a span name from the stashed SQL and
   * starts a JDBC span.
   *
   * @return the enter token returned by {@link SpanCollector#onEnter} (a start-nanos value), or
   * {@code -1L} when a same-kind parent already covers this call (nested call suppressed), or
   * {@code 0L} if entry itself failed
   */
  @OnMethodEnter
  public static long enter() {
    try {
      String sql = JdbcSqlHolder.get();
      String spanName = (sql == null || sql.isEmpty()) ? "JDBC.execute" : sql;
      return SpanCollector.onEnter(spanName, "JDBC");
    } catch (Throwable t) {
      SpanCollector.recordAdviceError();
      return 0L;
    }
  }

  /**
   * Runs after the instrumented execute-family call, including when it throws: completes the span
   * and pops the stashed SQL.
   *
   * @param token  the value returned by {@link #enter}, bound via {@link Enter @Enter}
   * @param thrown the exception thrown by the instrumented method, or {@code null} on normal
   *               return, bound via {@link Thrown @Thrown}
   */
  @OnMethodExit(onThrowable = Throwable.class)
  public static void exit(@Enter long token, @Thrown Throwable thrown) {
    try {
      SpanCollector.onExit(token, thrown);
    } catch (Throwable t) {
      SpanCollector.recordAdviceError();
    } finally {
      JdbcSqlHolder.clear();
    }
  }
}
