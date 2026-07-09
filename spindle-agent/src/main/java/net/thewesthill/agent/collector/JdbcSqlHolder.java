package net.thewesthill.agent.collector;

import java.util.ArrayDeque;

/**
 * Thread-local holder that bridges a JDBC statement's <em>prepare</em> phase to its
 * <em>execute</em> phase.
 * <p>
 * When an application calls {@code Connection.prepareStatement(sql)}, the SQL text is the method
 * argument; but the later call to {@code PreparedStatement.execute()} that actually runs the query
 * does not carry the SQL, so the instrumented execute advice would otherwise have no span name.
 * <p>
 * This holder closes that gap. The prepare advice
 * ({@link net.thewesthill.agent.advice.JdbcPrepareAdvice}) pushes the SQL onto a per-thread stack via
 * {@link #set}; the execute advice ({@link net.thewesthill.agent.advice.JdbcExecuteAdvice}) peeks the
 * top via {@link #get} to name the span, then pops it via {@link #clear} once the span is
 * reported.
 * <p>
 * A stack rather than a single slot is used so that nested prepared statements on the same thread
 * are each paired with the correct SQL. Storage is per-thread via {@link ThreadLocal}, so the
 * holder is inherently thread-safe without synchronization — each thread sees only its own stack.
 *
 * @author Jonah Fan
 * @since 1.0.0
 */
public final class JdbcSqlHolder {

  /**
   * Per-thread stack of SQL statements awaiting execution, newest first.
   */
  private static final ThreadLocal<ArrayDeque<String>> SQL_STACK =
      ThreadLocal.withInitial(ArrayDeque::new);

  /**
   * Private constructor to prevent instantiation.
   */
  private JdbcSqlHolder() {
  }

  /**
   * Pushes a SQL statement onto the current thread's stack.
   * <p>
   * Called by the prepare advice when a prepared statement is constructed.
   *
   * @param sql the SQL statement to associate with the next execute on this thread; never
   *            {@code null}
   */
  public static void set(String sql) {
    SQL_STACK.get().push(sql);
  }

  /**
   * Returns the SQL statement at the top of the current thread's stack without removing it.
   * <p>
   * Called by the execute advice to name the span.
   *
   * @return the most recently pushed SQL, or {@code null} if the stack is empty (e.g. the statement
   * was not prepared through an instrumented path)
   */
  public static String get() {
    ArrayDeque<String> stack = SQL_STACK.get();
    return stack.isEmpty() ? null : stack.peek();
  }

  /**
   * Pops the SQL statement at the top of the current thread's stack.
   * <p>
   * Called by the execute advice after the span is reported, to keep the stack balanced with the
   * prepare/execute nesting. A no-op when the stack is already empty.
   */
  public static void clear() {
    ArrayDeque<String> stack = SQL_STACK.get();
    if (!stack.isEmpty()) {
      stack.pop();
    }
  }
}
