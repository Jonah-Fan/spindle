package net.thewesthill.agent.log;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.function.Supplier;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import net.thewesthill.agent.config.AgentConfig;

/**
 * Static logging facade for the trace agent, layered over a single {@link Logger} named
 * {@code "spindle"}.
 * <p>
 * Serves two responsibilities:
 * <ul>
 *   <li><b>Bootstrapping</b> — {@link #init} configures the logger from an {@link AgentConfig}:
 *       detaches from the parent JUL handlers (no double-logging), then installs a console handler
 *       (fixed at {@link Level#INFO}) and a rotating file handler driven by
 *       {@link AgentConfig#logLevel()}, {@link AgentConfig#logDir()},
 *       {@link AgentConfig#logSize()} and {@link AgentConfig#logCount()}.</li>
 *   <li><b>Emitting</b> — {@link #debug}/{@link #info}/{@link #warn}/{@link #error} are the leveled
 *       entry points used across the agent (premain, builder, reporter, config sources, viewer).</li>
 * </ul>
 * <p>
 * Message templates use slf4j-style {@code {}} placeholders, resolved by the homegrown
 * {@link #formatMessage}; this is <em>not</em> {@link java.util.Formatter} or
 * {@code java.text.MessageFormat} syntax. Every line is rendered by {@link TraceFormatter} as
 * {@code <timestamp> [spindle] <LEVEL> <message>} followed by an optional stack trace.
 * <p>
 * All members are static; thread-safety is inherited from the underlying {@link Logger}.
 *
 * @author Jonah Fan
 * @since 1.0.0
 */
public final class AgentLog {

  /**
   * Prefix prepended to every formatted log line, marking agent output.
   */
  private static final String PREFIX = "[spindle] ";

  /**
   * The single backing JUL logger, named {@code "spindle"}.
   */
  private static final Logger LOG = Logger.getLogger("spindle");

  /**
   * Timestamp format for both log-line rendering and log-file naming:
   * {@code yyyy-MM-dd HH-mm-ss.SSS}.
   */
  private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern(
      "yyyy-MM-dd HH-mm-ss.SSS");

  /**
   * Private constructor to prevent instantiation.
   */
  private AgentLog() {
  }

  /**
   * Configures the agent logger from the supplied {@link AgentConfig}.
   * <p>
   * Detaches from the parent JUL logger ({@code setUseParentHandlers(false)}) so messages are not
   * double-emitted through the root handler, then replaces any existing handlers with the agent's
   * own: a console handler (always {@link Level#INFO}, so debug is console-suppressed even when the
   * file logs at {@link Level#FINE}) and a rotating file handler whose level, directory, size and
   * count come from the config. Both handlers share {@link TraceFormatter}.
   * <p>
   * Must be called during {@code premain} before any other {@code AgentLog} call; subsequent calls
   * reconfigure the logger.
   *
   * @param cfg the agent configuration driving level, directory, and rotation
   */
  public static void init(AgentConfig cfg) {
    Level level = cfg.logLevel();
    LOG.setUseParentHandlers(false);
    LOG.setLevel(level);
    removeHandlers();

    Formatter formatter = new TraceFormatter();
    addConsoleHandler(formatter);
    addFileHandler(cfg, formatter);
  }

  /**
   * Logs a message at {@link Level#FINE}, substituting {@code {}} placeholders.
   * <p>
   * Skipped (no formatting work) when FINE is below the logger's effective level.
   *
   * @param msg  the message template, using {@code {}} for each positional argument
   * @param args the arguments to substitute into the template
   */
  public static void debug(String msg, Object... args) {
    log(Level.FINE, msg, null, args);
  }

  /**
   * Logs a message at {@link Level#INFO}, substituting {@code {}} placeholders.
   * <p>
   * Skipped (no formatting work) when INFO is below the logger's effective level.
   *
   * @param msg  the message template, using {@code {}} for each positional argument
   * @param args the arguments to substitute into the template
   */
  public static void info(String msg, Object... args) {
    log(Level.INFO, msg, null, args);
  }

  /**
   * Logs a message at {@link Level#WARNING}, substituting {@code {}} placeholders.
   * <p>
   * Skipped (no formatting work) when WARNING is below the logger's effective level.
   *
   * @param msg  the message template, using {@code {}} for each positional argument
   * @param args the arguments to substitute into the template
   */
  public static void warn(String msg, Object... args) {
    log(Level.WARNING, msg, null, args);
  }

  /**
   * Logs a message at {@link Level#WARNING} with an attached throwable, substituting {@code {}}
   * placeholders. The throwable's stack trace is appended by {@link TraceFormatter}.
   * <p>
   * Skipped (no formatting work) when WARNING is below the logger's effective level.
   *
   * @param msg  the message template, using {@code {}} for each positional argument
   * @param t    the throwable to associate with the record; its stack trace is rendered inline
   * @param args the arguments to substitute into the template
   */
  public static void warn(String msg, Throwable t, Object... args) {
    log(Level.WARNING, msg, t, args);
  }

  /**
   * Logs a message at {@link Level#SEVERE}, substituting {@code {}} placeholders.
   * <p>
   * Skipped (no formatting work) when SEVERE is below the logger's effective level.
   *
   * @param msg  the message template, using {@code {}} for each positional argument
   * @param args the arguments to substitute into the template
   */
  public static void error(String msg, Object... args) {
    log(Level.SEVERE, msg, null, args);
  }

  /**
   * Lazily logs a message at {@link Level#FINE}.
   * <p>
   * The supplier is only evaluated when FINE is loggable, making this suitable for hot paths where
   * building the message is itself expensive. Reserved for future use — currently has no call sites
   * (hence {@code @SuppressWarnings("unused")}).
   *
   * @param supplier supplies the message only when fine logging is enabled
   */
  @SuppressWarnings("unused")
  public static void debug(Supplier<String> supplier) {
    if (LOG.isLoggable(Level.FINE)) {
      LOG.fine(supplier.get());
    }
  }

  /**
   * Lazily logs a message at {@link Level#INFO}.
   * <p>
   * The supplier is only evaluated when INFO is loggable, making this suitable for hot paths where
   * building the message is itself expensive. Reserved for future use — currently has no call sites
   * (hence {@code @SuppressWarnings("unused")}).
   *
   * @param supplier supplies the message only when info logging is enabled
   */
  @SuppressWarnings("unused")
  public static void info(Supplier<String> supplier) {
    if (LOG.isLoggable(Level.INFO)) {
      LOG.info(supplier.get());
    }
  }

  /**
   * Internal dispatcher: formats the template and forwards to the JUL logger at the given level,
   * attaching the throwable when present.
   * <p>
   * Returns without formatting when the level is below the logger's effective threshold.
   *
   * @param level the JUL level to log at
   * @param msg   the message template, using {@code {}} placeholders
   * @param t     an optional throwable to attach, or {@code null}
   * @param args  the arguments to substitute into the template
   */
  private static void log(Level level, String msg, Throwable t, Object... args) {
    if (!LOG.isLoggable(level)) {
      return;
    }
    String formatted = formatMessage(msg, args);
    if (t != null) {
      LOG.log(level, formatted, t);
    } else {
      LOG.log(level, formatted);
    }
  }

  /**
   * Substitutes slf4j-style {@code {}} placeholders in the template with successive arguments (via
   * {@link StringBuilder#append(Object)}, i.e. {@code toString}).
   * <p>
   * Behavior:
   * <ul>
   *   <li>No arguments — the template is returned verbatim.</li>
   *   <li>Each {@code {}} consumes the next argument; once arguments are exhausted, remaining
   *       {@code {}} sequences are left literal.</li>
   *   <li>A lone {@code '{'} not immediately followed by {@code '}'} is output as-is — this is
   *       <em>not</em> {@code java.text.MessageFormat} syntax, so forms like {@code {0}} are not
   *       resolved.</li>
   * </ul>
   * <p>
   * Also invoked by {@link TraceFormatter#format} to resolve a {@link LogRecord}'s parameters, so
   * the same {@code {}} contract applies to anything routed through JUL directly.
   *
   * @param template the message template
   * @param args     the arguments to substitute
   * @return the formatted message
   */
  private static String formatMessage(String template, Object... args) {
    if (args == null || args.length == 0) {
      return template;
    }
    StringBuilder sb = new StringBuilder(template.length() + args.length * 8);
    int argIdx = 0;
    for (int i = 0; i < template.length(); i++) {
      char c = template.charAt(i);
      if (c == '{' && i + 1 < template.length() && template.charAt(i + 1) == '}'
          && argIdx < args.length) {
        sb.append(args[argIdx++]);
        i++;
      } else {
        sb.append(c);
      }
    }
    return sb.toString();
  }

  /**
   * Installs a {@link ConsoleHandler} on the logger, fixed at {@link Level#INFO} so debug output
   * never reaches the console even when the file handler logs at {@link Level#FINE}.
   *
   * @param fmt the formatter to apply to console output
   */
  private static void addConsoleHandler(Formatter fmt) {
    ConsoleHandler console = new ConsoleHandler();
    console.setLevel(Level.INFO);
    console.setFormatter(fmt);
    LOG.addHandler(console);
  }

  /**
   * Installs a rotating {@link FileHandler} on the logger, creating the log directory if needed.
   * <p>
   * Files are named {@code spindle-<timestamp>.log} inside {@link AgentConfig#logDir()}, with
   * size/count rotation ({@link AgentConfig#logSize()}, {@link AgentConfig#logCount()}) and append
   * mode. The handler's level mirrors {@link AgentConfig#logLevel()}. If the file cannot be opened,
   * a warning is emitted and the logger falls back to console-only.
   *
   * @param cfg the agent configuration driving directory and rotation
   * @param fmt the formatter to apply to file output
   */
  private static void addFileHandler(AgentConfig cfg, Formatter fmt) {
    String dir = cfg.logDir();
    long size = cfg.logSize();
    int count = cfg.logCount();

    try {
      Path dirPath = Paths.get(dir);
      if (!Files.isDirectory(dirPath)) {
        Files.createDirectories(dirPath);
      }
      String stamp = LocalDateTime.now().format(TS_FMT);
      FileHandler file = new FileHandler(
          dirPath.resolve("spindle-" + stamp + ".log").toString(), size, count, true);
      file.setLevel(cfg.logLevel());
      file.setFormatter(fmt);
      LOG.addHandler(file);
    } catch (IOException e) {
      AgentLog.warn("Failed to open log file in {}, console-only: {}", e, dir);
    }
  }

  /**
   * Removes every handler currently attached to the logger. Called during {@link #init} so
   * reconfiguration does not stack duplicate handlers.
   */
  private static void removeHandlers() {
    for (Handler h : LOG.getHandlers()) {
      LOG.removeHandler(h);
    }
  }

  /**
   * JUL {@link Formatter} that renders records as
   * {@code <timestamp> [spindle] <LEVEL> <message>} followed by an optional throwable stack
   * trace. Parameter substitution is delegated to {@link AgentLog#formatMessage} so both the
   * varargs facade and direct JUL calls share the same {@code {}} contract.
   */
  private static final class TraceFormatter extends Formatter {

    /**
     * Formats a single log record into one logical line (plus an optional stack trace block).
     *
     * @param record the record to format
     * @return the formatted log text, terminated with a newline
     */
    @Override
    public String format(LogRecord record) {
      StringBuilder sb = new StringBuilder();
      sb.append(
              Instant.ofEpochMilli(record.getMillis()).atZone(ZoneId.systemDefault()).format(TS_FMT))
          .append(' ').append(PREFIX).append(record.getLevel().getName()).append(' ')
          .append(AgentLog.formatMessage(record.getMessage(), record.getParameters()))
          .append('\n');

      Throwable t = record.getThrown();
      if (t != null) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        sb.append(sw);
      }
      return sb.toString();
    }
  }
}
