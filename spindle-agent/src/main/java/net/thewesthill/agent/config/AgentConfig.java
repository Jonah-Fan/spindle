package net.thewesthill.agent.config;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

/**
 * Immutable configuration for the trace agent, parsed from the agent argument string.
 * <p>
 * A record carrying the runtime knobs consumed across the agent: the trace database path, the
 * optional embedded viewer, the external interceptor-config path, and logging settings. Created
 * exclusively via {@link #parse(String)}, which decodes the {@code key=value,key=value} argument
 * string supplied on the JVM command line (e.g. via {@code -javaagent:spindle-agent.jar=db=...}),
 * applying sensible defaults for any key that is missing or malformed.
 *
 * @param dbPath        path to the SQLite database file (default {@code "trace.db"})
 * @param viewerPort    TCP port for the embedded HTTP viewer (default {@code 8787})
 * @param viewerEnabled whether the HTTP viewer is started; {@code false} when the {@code viewer}
 *                      argument is set to {@code "off"} (default {@code true})
 * @param configPath    optional explicit path to an interceptor-config YAML, or {@code null}
 * @param logDir        log file directory (default {@code "./logs"})
 * @param logLevel      log level, derived from the {@code logLevel} argument (default {@link Level#INFO})
 * @param logSize       log file rotation size in bytes (default 50 MB)
 * @param logCount      number of rotated log files to retain (default 100)
 *
 * @author Jonah Fan
 * @since 1.0.0
 */
public record AgentConfig(
    String dbPath,
    int viewerPort,
    boolean viewerEnabled,
    String configPath,
    String logDir,
    Level logLevel,
    long logSize,
    int logCount
) {

  /**
   * Parses the agent argument string into a configuration, applying defaults for missing or invalid
   * values.
   * <p>
   * Recognized keys: {@code db}, {@code port}, {@code viewer}, {@code config}, {@code logDir},
   * {@code logLevel}, {@code logSize}, {@code logCount}. Unknown keys are ignored. The
   * {@code viewer} key disables the viewer only when set to {@code "off"} (case-insensitive).
   *
   * @param agentArgs the {@code key=value,key=value} argument string, or {@code null}/empty for all
   *                  defaults
   * @return a populated configuration; never {@code null}
   */
  public static AgentConfig parse(String agentArgs) {
    Map<String, String> args = parseArgs(agentArgs);

    return new AgentConfig(
        args.getOrDefault("db", "trace.db"),
        parseInt(args.getOrDefault("port", "8787"), 8787),
        !"off".equalsIgnoreCase(args.get("viewer")),
        args.get("config"),
        args.getOrDefault("logDir", "./logs"),
        parseLevel(args.getOrDefault("logLevel", "info")),
        parseLong(args.getOrDefault("logSize", String.valueOf(50L * 1024 * 1024))),
        parseInt(args.getOrDefault("logCount", "100"), 100)
    );
  }

  /**
   * Splits the argument string into a key/value map. Pairs are {@code key=value} separated by
   * commas; whitespace around keys and values is trimmed. A pair with no {@code =} maps its trimmed
   * form to the empty string. A {@code null} or empty argument string yields an empty map.
   *
   * @param args the raw argument string, or {@code null}
   * @return the parsed key/value pairs, never {@code null}
   */
  private static Map<String, String> parseArgs(String args) {
    Map<String, String> map = new HashMap<>();
    if (args == null || args.isEmpty()) {
      return map;
    }
    for (String pair : args.split(",")) {
      int idx = pair.indexOf('=');
      if (idx > 0) {
        map.put(pair.substring(0, idx).trim(), pair.substring(idx + 1).trim());
      } else {
        map.put(pair.trim(), "");
      }
    }
    return map;
  }

  /**
   * Translates a level name into a {@link Level}, case-insensitively: {@code debug} →
   * {@link Level#FINE}, {@code warn} → {@link Level#WARNING}, {@code error} → {@link Level#SEVERE},
   * anything else (including {@code null}) → {@link Level#INFO}.
   *
   * @param s the level name, or {@code null}
   * @return the corresponding logging level
   */
  private static Level parseLevel(String s) {
    if (s == null) {
      return Level.INFO;
    }
    return switch (s.toLowerCase()) {
      case "debug" -> Level.FINE;
      case "warn" -> Level.WARNING;
      case "error" -> Level.SEVERE;
      default -> Level.INFO;
    };
  }

  /**
   * Parses an integer, returning {@code def} when {@code s} is not a valid integer.
   *
   * @param s   the string to parse, or {@code null}
   * @param def the fallback value
   * @return the parsed integer, or {@code def}
   */
  private static int parseInt(String s, int def) {
    try {
      return Integer.parseInt(s);
    } catch (NumberFormatException e) {
      return def;
    }
  }

  /**
   * Parses a long, falling back to 50 MB when {@code s} is not a valid long.
   *
   * @param s the string to parse, or {@code null}
   * @return the parsed long, or {@code 50L * 1024 * 1024}
   */
  private static long parseLong(String s) {
    try {
      return Long.parseLong(s);
    } catch (NumberFormatException e) {
      return 50L * 1024 * 1024;
    }
  }
}
