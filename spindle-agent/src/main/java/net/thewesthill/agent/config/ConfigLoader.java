package net.thewesthill.agent.config;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import net.thewesthill.agent.config.source.ClasspathConfigSource;
import net.thewesthill.agent.config.source.ConfigSource;
import net.thewesthill.agent.config.source.FileConfigSource;

/**
 * Resolves the interceptor-rule configuration into a {@link ConfigSource} lookup list and loads the
 * first source that yields rules.
 * <p>
 * A {@link ConfigLoader} is a strategy ({@link #sources(AgentConfig)}) that, given the agent config,
 * produces an ordered list of {@link ConfigSource}s to consult. The agent ships two ready-made
 * strategies:
 * <ul>
 *   <li>{@link #development()} — looks for an explicit config path (if set), then a local
 *       {@code spindle-interceptors.yml} file, then the same name on the classpath, finally falling
 *       back to the bundled {@code defaults-interceptors.yml} on the classpath.</li>
 *   <li>{@link #production()} — an explicit config path (if set), then only the bundled
 *       {@code defaults-interceptors.yml} on the classpath (no local file lookup).</li>
 * </ul>
 * {@link #load} walks the sources in order and returns the first non-empty rule list; if no source
 * provides rules, an empty list is returned and the agent runs with no interceptors.
 *
 * @author Jonah Fan
 * @since 1.0.0
 * @see ConfigSource
 */
@SuppressWarnings("unused")
@FunctionalInterface
public interface ConfigLoader {

  /**
   * Returns the development config-source lookup list.
   * <p>
   * Order (first non-empty wins):
   * <ol>
   *   <li>the file at {@link AgentConfig#configPath()}, when that path is set and non-empty;</li>
   *   <li>{@code ./spindle-interceptors.yml} on the filesystem;</li>
   *   <li>{@code /spindle-interceptors.yml} on the classpath;</li>
   *   <li>{@code /defaults-interceptors.yml} on the classpath (the bundled defaults).</li>
   * </ol>
   *
   * @return a loader producing the development source list
   */
  static ConfigLoader development() {
    return cfg -> {
      List<ConfigSource> sources = new ArrayList<>();
      if (cfg.configPath() != null && !cfg.configPath().isEmpty()) {
        sources.add(new FileConfigSource(Path.of(cfg.configPath())));
      }
      sources.add(new FileConfigSource(Path.of("spindle-interceptors.yml")));
      sources.add(new ClasspathConfigSource("/spindle-interceptors.yml"));
      sources.add(new ClasspathConfigSource("/defaults-interceptors.yml"));
      return sources;
    };
  }

  /**
   * Returns the production config-source lookup list.
   * <p>
   * Order (first non-empty wins):
   * <ol>
   *   <li>the file at {@link AgentConfig#configPath()}, when that path is set and non-empty;</li>
   *   <li>{@code /defaults-interceptors.yml} on the classpath (the bundled defaults).</li>
   * </ol>
   * Production intentionally omits the local-file lookups of {@link #development()} so deployment
   * relies on an explicit path or the bundled defaults.
   *
   * @return a loader producing the production source list
   */
  static ConfigLoader production() {
    return cfg -> {
      List<ConfigSource> sources = new ArrayList<>();
      if (cfg.configPath() != null && !cfg.configPath().isEmpty()) {
        sources.add(new FileConfigSource(Path.of(cfg.configPath())));
      }
      sources.add(new ClasspathConfigSource("/defaults-interceptors.yml"));
      return sources;
    };
  }

  /**
   * Loads the interceptor rules by walking {@code loader}'s sources in order and returning the first
   * non-empty result. Returns an empty list if no source yields any rules.
   *
   * @param cfg    the parsed agent configuration, passed to the loader to build its sources
   * @param loader the strategy that produces the ordered {@link ConfigSource} list
   * @return the first non-empty rule list found, or {@link List#of()} if none
   */
  static List<InterceptorRule> load(AgentConfig cfg, ConfigLoader loader) {
    for (ConfigSource source : loader.sources(cfg)) {
      List<InterceptorRule> rules = source.load();
      if (rules != null && !rules.isEmpty()) {
        return rules;
      }
    }
    return List.of();
  }

  /**
   * Produces the ordered list of {@link ConfigSource}s to consult for the given configuration.
   *
   * @param cfg the parsed agent configuration
   * @return the ordered config sources (first non-empty wins)
   */
  List<ConfigSource> sources(AgentConfig cfg);
}
