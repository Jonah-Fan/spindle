package net.thewesthill.agent.config.source;

import java.io.InputStream;
import java.util.List;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import net.thewesthill.agent.config.InterceptorRule;

/**
 * A source of interceptor-rule configuration, consumed by {@link net.thewesthill.agent.config.ConfigLoader}.
 * <p>
 * Each source knows how to {@link #load()} its rules; the loader consults sources in order and uses
 * the first that returns a non-empty list. The two implementations are {@link FileConfigSource}
 * (filesystem YAML) and {@link ClasspathConfigSource} (classpath resource YAML). The shared
 * {@link #parse(InputStream)} default parses a YAML stream into
 * {@link InterceptorRule.ConfigFile} and extracts its {@code interceptors} list.
 *
 * @author Jonah Fan
 * @since 1.0.0
 * @see FileConfigSource
 * @see ClasspathConfigSource
 */
@FunctionalInterface
public interface ConfigSource {

  /**
   * Loads this source's interceptor rules.
   * <p>
   * Implementations return {@code null} (rather than an empty list) to signal that the source does
   * not exist or could not be read, so the loader can distinguish "no source here" from "source
   * present but defines no rules". A parse error is logged and reported as {@code null}.
   *
   * @return the parsed rules, possibly empty; or {@code null} if the source is absent or failed
   */
  List<InterceptorRule> load();

  /**
   * Parses a YAML input stream into interceptor rules, shared by the file and classpath sources.
   * <p>
   * Deserializes into {@link InterceptorRule.ConfigFile} via SnakeYAML and returns its
   * {@code interceptors} list, or an empty list when the file is empty or declares no
   * {@code interceptors}. The caller is responsible for closing {@code in}.
   *
   * @param in the YAML input stream; not closed by this method
   * @return the parsed rules, possibly empty but never {@code null}
   */
  default List<InterceptorRule> parse(InputStream in) {
    Yaml yaml = new Yaml(new Constructor(InterceptorRule.ConfigFile.class, new LoaderOptions()));
    InterceptorRule.ConfigFile cfg = yaml.load(in);
    if (cfg == null || cfg.interceptors == null) {
      return List.of();
    }
    return cfg.interceptors;
  }
}
