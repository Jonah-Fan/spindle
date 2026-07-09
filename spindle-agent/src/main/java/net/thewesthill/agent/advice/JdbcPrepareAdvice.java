package net.thewesthill.agent.advice;

import net.bytebuddy.asm.Advice.Argument;
import net.bytebuddy.asm.Advice.OnMethodEnter;

import net.thewesthill.agent.collector.JdbcSqlHolder;
import net.thewesthill.agent.collector.SpanCollector;

/**
 * ByteBuddy advice injected at the entry of {@link java.sql.Connection#prepareStatement(String)}.
 * <p>
 * Captures the SQL text supplied when a prepared statement is constructed and stashes it on the
 * current thread via {@link JdbcSqlHolder#set}. The later execute advice
 * ({@link JdbcExecuteAdvice}) reads it back to name the resulting span, since
 * {@link java.sql.PreparedStatement#execute()} carries no SQL of its own.
 * <p>
 * Registered for the {@code jdbc} interceptor in {@code defaults-interceptors.yml} against
 * {@code prepareStatement} taking a single {@code String} argument.
 * <p>
 * The body is wrapped in a try/catch that records any failure via
 * {@link SpanCollector#recordAdviceError} and otherwise swallows it — instrumentation must never
 * throw into the host application's business code.
 *
 * @author Jonah Fan
 * @since 1.0.0
 */
@SuppressWarnings("unused")
public final class JdbcPrepareAdvice {

  /**
   * Runs before the instrumented {@code prepareStatement} call: records the SQL for the matching
   * execute.
   *
   * @param sql the SQL text passed to {@code prepareStatement}, bound from the method's first
   *            argument via {@link Argument @Argument(0)}
   */
  @OnMethodEnter
  public static void enter(@Argument(0) String sql) {
    try {
      if (sql != null) {
        JdbcSqlHolder.set(sql);
      }
    } catch (Throwable t) {
      SpanCollector.recordAdviceError();
    }
  }
}
