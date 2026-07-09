package net.thewesthill.agent.config.source;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import net.thewesthill.agent.config.InterceptorRule;
import net.thewesthill.agent.log.AgentLog;

/**
 * A {@link ConfigSource} that reads interceptor rules from a filesystem YAML file.
 * <p>
 * Loads the file at {@link #path}; returns {@code null} when the file does not exist or reading fails,
 * so the loader falls through to the next source. Logs the absolute path when a non-empty rule set
 * is loaded.
 *
 * @author Jonah Fan
 * @since 1.0.0
 * @see ConfigSource
 */
public final class FileConfigSource implements ConfigSource {

  /** The filesystem path of the YAML config file. */
  private final Path path;

  /**
   * Creates a source for the given file path.
   *
   * @param path the file to read
   */
  public FileConfigSource(Path path) {
    this.path = path;
  }

  /**
   * Loads and parses the file. Returns {@code null} when the file does not exist or the read fails
   * (logged as a warning); otherwise the parsed rules, logging the absolute path on a non-empty
   * result.
   *
   * @return the parsed rules, possibly empty; or {@code null} if the file is absent or failed
   */
  @Override
  public List<InterceptorRule> load() {
    if (!Files.exists(path)) {
      return null;
    }

    try (InputStream in = Files.newInputStream(path)) {
      List<InterceptorRule> rules = parse(in);
      if (!rules.isEmpty()) {
        AgentLog.info("loaded config from file: " + path.toAbsolutePath());
      }
      return rules;
    } catch (IOException e) {
      AgentLog.warn("read config file failed: " + path, e);
      return null;
    }
  }
}
