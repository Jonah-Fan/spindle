package net.thewesthill.agent;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.List;

import net.thewesthill.agent.builder.AgentBuilderProvider;
import net.thewesthill.agent.config.AgentConfig;
import net.thewesthill.agent.log.AgentLog;
import net.thewesthill.agent.reporter.SqliteReporter;
import net.thewesthill.agent.server.HttpViewerServer;

/**
 * Entry point and lifecycle manager for the trace agent.
 * <p>
 * A JVM instrumentation agent that records distributed traces via ByteBuddy bytecode rewriting. On
 * startup, it parses its configuration (see {@link AgentConfig#parse}), initializes logging, starts
 * the span storage reporter ({@link SqliteReporter}) and — when the viewer is enabled — the
 * embedded HTTP viewer ({@link HttpViewerServer}), installs the interceptor rules built by
 * {@link AgentBuilderProvider#build} onto the supplied {@link Instrumentation}, and registers a JVM
 * shutdown hook that releases all started resources in reverse order.
 * <p>
 * All startup work is wrapped so that any failure disables the agent without propagating into the
 * host application — instrumentation must never throw into business code.
 *
 * @author Jonah Fan
 * @since 1.0.0
 */
public final class TraceAgent {

  /**
   * Span storage reporter that persists completed spans to a SQLite database. Held as a field to
   * retain a strong reference for the agent's lifetime.
   */
  @SuppressWarnings("FieldCanBeLocal")
  private final SqliteReporter reporter;
  /**
   * Embedded HTTP viewer serving the trace database, or {@code null} when the viewer is disabled.
   * Held as a field to retain a strong reference for the agent's lifetime.
   */
  @SuppressWarnings("FieldCanBeLocal")
  private final HttpViewerServer viewer;
  /**
   * Closeable resources started by this agent, in the order they were added; closed in reverse
   * order on shutdown.
   */
  private final List<AutoCloseable> resources = new ArrayList<>();

  /**
   * Creates and starts the agent's runtime components from the given configuration.
   * <p>
   * Starts the {@link SqliteReporter} against {@link AgentConfig#dbPath()} and, when
   * {@link AgentConfig#viewerEnabled()} is {@code true}, the {@link HttpViewerServer} serving the
   * absolute database path on {@link AgentConfig#viewerPort()}. Each started component is
   * registered in {@link #resources} for orderly shutdown.
   *
   * @param cfg the parsed agent configuration
   */
  private TraceAgent(AgentConfig cfg) {
    this.reporter = new SqliteReporter(cfg.dbPath());
    this.reporter.start();
    this.resources.add(this.reporter);

    if (cfg.viewerEnabled()) {
      this.viewer = new HttpViewerServer(
          new File(cfg.dbPath()).getAbsolutePath(),
          cfg.viewerPort()
      );
      this.viewer.start();
      this.resources.add(this.viewer);
    } else {
      this.viewer = null;
    }
  }

  /**
   * JVM agent entry point invoked before the application's {@code main} method.
   * <p>
   * Parses the agent arguments into an {@link AgentConfig}, initializes logging, constructs the
   * agent (starting its reporter and optional viewer), registers the shutdown hook, installs the
   * ByteBuddy {@link net.bytebuddy.agent.builder.AgentBuilder} onto {@code inst}, and logs the
   * startup banner.
   * <p>
   * Any {@link Throwable} thrown during startup is logged and swallowed — the agent disables itself
   * rather than failing the host application.
   *
   * @param agentArgs the agent argument string in {@code key=value,key=value} form, may be
   *                  {@code null} or empty to use defaults
   * @param inst      the JVM-provided instrumentation handle used to install transformers
   * @see Instrumentation
   */
  public static void premain(String agentArgs, Instrumentation inst) {
    AgentConfig cfg = AgentConfig.parse(agentArgs);
    AgentLog.init(cfg);

    try {
      TraceAgent agent = new TraceAgent(cfg);
      agent.registerShutdownHook();
      AgentBuilderProvider.build(cfg).installOn(inst);
      agent.logStartup(cfg);
    } catch (Throwable t) {
      AgentLog.error("premain failed, agent disabled", t);
    }
  }

  /**
   * JVM agent entry point invoked when the agent is attached to an already-running VM (via the
   * Attachment API). Delegates to {@link #premain} so attach-time and launch-time startup are
   * identical.
   *
   * @param agentArgs the agent argument string, may be {@code null} or empty
   * @param inst      the JVM-provided instrumentation handle used to install transformers
   * @see #premain
   */
  public static void agentmain(String agentArgs, java.lang.instrument.Instrumentation inst) {
    premain(agentArgs, inst);
  }

  /**
   * Registers a JVM shutdown hook (thread {@code "spindle-shutdown"}) that runs {@link #shutdown} on
   * VM termination.
   */
  private void registerShutdownHook() {
    Runtime.getRuntime().addShutdownHook(
        new Thread(this::shutdown, "spindle-shutdown")
    );
  }

  /**
   * Closes every resource in {@link #resources} in reverse insertion order (last started, first
   * closed) and logs a warning for any that fail to close, swallowing the failure so one stuck
   * resource does not prevent the rest from closing.
   */
  private void shutdown() {
    for (int i = resources.size() - 1; i >= 0; i--) {
      try {
        resources.get(i).close();
      } catch (Throwable t) {
        AgentLog.warn("failed to close resource: {}", resources.get(i).getClass().getSimpleName(), t);
      }
    }
  }

  /**
   * Logs the startup banner: the database path, viewer status (URL when enabled, {@code off}
   * otherwise), and — when present — the external config path.
   *
   * @param cfg the parsed agent configuration
   */
  private void logStartup(AgentConfig cfg) {
    StringBuilder sb = new StringBuilder("Spindle started. db=").append(cfg.dbPath());
    sb.append(cfg.viewerEnabled()
        ? " viewer=http://localhost:" + cfg.viewerPort() + "/"
        : " viewer=off");
    if (cfg.configPath() != null) {
      sb.append(" config=").append(cfg.configPath());
    }
    AgentLog.info(sb.toString());
  }
}
