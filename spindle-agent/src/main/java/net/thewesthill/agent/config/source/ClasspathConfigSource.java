package net.thewesthill.agent.config.source;

import java.io.InputStream;
import java.util.List;

import net.thewesthill.agent.config.InterceptorRule;
import net.thewesthill.agent.log.AgentLog;

/**
 * A {@link ConfigSource} that reads interceptor rules from a classpath resource.
 * <p>
 * Loads the YAML at {@link #resource} via {@link Class#getResourceAsStream(String)} on
 * {@link ConfigSource} (the agent's own classpath). Returns {@code null} when the resource is
 * absent or parsing fails, so the loader falls through to the next source. Logs the resource path
 * when a non-empty rule set is loaded.
 *
 * @author Jonah Fan
 * @see ConfigSource
 * @since 1.0.0
 */
public final class ClasspathConfigSource implements ConfigSource {

  /**
   * The classpath resource path to load, e.g. {@code "/defaults-interceptors.yml"}.
   */
  private final String resource;

  /**
   * Creates a source for the given classpath resource.
   *
   * @param resource the classpath resource path; should start with {@code /}
   */
  public ClasspathConfigSource(String resource) {
    this.resource = resource;
  }

  /**
   * Loads and parses the resource. Returns {@code null} when the resource is missing or the parse
   * fails (logged as a warning); otherwise the parsed rules, logging the resource path on a
   * non-empty result.
   *
   * @return the parsed rules, possibly empty; or {@code null} if the resource is absent or failed
   */
  @Override
  public List<InterceptorRule> load() {
    try (InputStream in = ConfigSource.class.getResourceAsStream(resource)) {
      if (in == null) {
        return null;
      }
      List<InterceptorRule> rules = parse(in);
      if (!rules.isEmpty()) {
        AgentLog.info("loaded config from classpath: " + resource);
      }
      return rules;
    } catch (Exception e) {
      AgentLog.warn("parse config failed: " + resource, e);
      return null;
    }
  }
}
