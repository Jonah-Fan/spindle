package net.thewesthill.agent.builder;

import java.util.List;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;

import net.thewesthill.agent.config.AgentConfig;
import net.thewesthill.agent.config.ConfigLoader;
import net.thewesthill.agent.config.InterceptorRule;
import net.thewesthill.agent.config.MatcherCompiler;
import net.thewesthill.agent.config.MatcherCompiler.Compiled;
import net.thewesthill.agent.log.AgentLog;

/**
 * Assembles the ByteBuddy {@link AgentBuilder} that installs the trace agent's interceptor rules.
 * <p>
 * Loads the interceptor rules via {@link ConfigLoader#load}, then compiles each rule through
 * {@link MatcherCompiler} into a type/method matcher plus advice transformer, and registers them on
 * a base {@link AgentBuilder} configured for retransformation-based redefinition. Rules are applied
 * independently: a rule that fails to compile is logged and skipped so one bad rule does not disable
 * the rest. The resulting builder is installed onto the {@link java.lang.instrument.Instrumentation}
 * by {@link net.thewesthill.agent.TraceAgent#premain}.
 * <p>
 * A global ignore matcher ({@link #baseIgnoreMatcher}) keeps the agent from instrumenting JDK,
 * framework, and agent-internal types that should not be traced (and would risk recursion). Selected
 * JDK types are carved back out of that ignore set so the rules that target them still apply — see
 * the method documentation for the exact logic.
 *
 * @author Jonah Fan
 * @since 1.0.0
 */
public final class AgentBuilderProvider {

  /**
   * Package prefixes excluded from instrumentation: JVM internals, the agent's own packages, and the
   * third-party libraries bundled into the agent (ByteBuddy, SQLite, SnakeYAML, Gson, Spring
   * internals, logging stacks). Matching any prefix here adds a type to the ignore set.
   */
  private static final List<String> IGNORE_PREFIXES = List.of(
      "sun.",
      "com.sun.",
      "jdk.",
      "net.bytebuddy.",
      "net.thewesthill.agent.",
      "net.thewesthill.trace.",
      "org.sqlite.",
      "org.yaml.",
      "com.google.gson",
      "org.springframework.asm.",
      "org.springframework.cglib.",
      "org.springframework.boot.loader.",
      "org.springframework.diagnostics.",
      "ch.qos.logback.",
      "org.apache.logging.",
      "org.slf4j."
  );

  /** Private constructor; this class exposes only static methods. */
  private AgentBuilderProvider() {
  }

  /**
   * Builds an agent from the given configuration, using the {@link ConfigLoader#development()
   * development} config-source loader (file/classpath lookup order).
   *
   * @param cfg the parsed agent configuration
   * @return a configured, rule-loaded {@link AgentBuilder}; never {@code null}
   */
  public static AgentBuilder build(AgentConfig cfg) {
    return build(cfg, ConfigLoader.development());
  }

  /**
   * Builds an agent from the given configuration and config-source loader.
   * <p>
   * Creates a base {@link AgentBuilder} in retransformation/redefine mode that ignores the
   * {@link #baseIgnoreMatcher() base ignore set}, then compiles each loaded {@link InterceptorRule}
   * via {@link MatcherCompiler#compile} and adds its type matcher and transformer. A compile failure
   * for one rule is logged (not thrown) and that rule is skipped. Logs the total rule count once all
   * rules are processed.
   *
   * @param cfg    the parsed agent configuration
   * @param loader the config-source loader selecting where rules are read from
   * @return a configured, rule-loaded {@link AgentBuilder}; never {@code null}
   */
  public static AgentBuilder build(AgentConfig cfg, ConfigLoader loader) {
    List<InterceptorRule> rules = ConfigLoader.load(cfg, loader);

    AgentBuilder builder =
        new AgentBuilder.Default()
            .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
            .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
            .ignore(baseIgnoreMatcher());

    MatcherCompiler compiler = new MatcherCompiler();
    for (InterceptorRule rule : rules) {
      try {
        Compiled compiled = compiler.compile(rule);
        builder = builder.type(compiled.typeMatcher()).transform(compiled.transformer());
      } catch (Throwable t) {
        AgentLog.warn("skip rule [" + rule.name + "]", t);
      }
    }

    AgentLog.info("installed " + rules.size() + " interceptor rule(s)");
    return builder;
  }

  /**
   * Builds the global ignore matcher applied via {@link AgentBuilder#ignore}. Any type matching it is
   * excluded from instrumentation.
   * <p>
   * The set is the union (all combined with {@code or}) of:
   * <ul>
   *   <li>{@code java.*} — <em>except</em> {@code java.sql.*}, which is carved out so the JDBC rule
   *       can instrument {@link java.sql.Connection}/{@link java.sql.PreparedStatement}, and
   *       <em>except</em> {@code java.util.concurrent.ThreadPoolExecutor}, carved out so the
   *       thread-pool rule can instrument {@code execute}.</li>
   *   <li>{@code javax.*} — except {@code javax.sql.*} (kept instrumental alongside JDBC).</li>
   *   <li>{@code jakarta.*} — excluded wholesale; the servlet rule targets user-defined
   *       {@code *HttpServlet} subclasses rather than the {@code jakarta.servlet} API types, so the
   *       API packages need not be instrumented.</li>
   *   <li>every prefix in {@link #IGNORE_PREFIXES}.</li>
   *   <li>synthetic types ({@link ElementMatchers#isSynthetic}).</li>
   * </ul>
   *
   * @return the element matcher identifying types the agent must not instrument
   */
  private static ElementMatcher<? super TypeDescription> baseIgnoreMatcher() {
    ElementMatcher.Junction<? super TypeDescription> matcher =
        ElementMatchers.nameStartsWith("java.")
            .and(ElementMatchers.not(ElementMatchers.nameStartsWith("java.sql.")))
            .and(ElementMatchers.not(
                ElementMatchers.named("java.util.concurrent.ThreadPoolExecutor")))
            .or(ElementMatchers.nameStartsWith("javax.")
                .and(ElementMatchers.not(ElementMatchers.nameStartsWith("javax.sql."))))
            .or(ElementMatchers.nameStartsWith("jakarta."));

    for (String prefix : IGNORE_PREFIXES) {
      matcher = matcher.or(ElementMatchers.nameStartsWith(prefix));
    }

    return matcher.or(ElementMatchers.isSynthetic());
  }
}
