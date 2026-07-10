package net.thewesthill.agent.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import net.bytebuddy.description.type.TypeDescription;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ConfigLoader} (loading the bundled defaults) and {@link MatcherCompiler}
 * (compiling rules into ByteBuddy type matchers).
 */
class ConfigAndMatcherTest {

  /** Expected rule names in the bundled defaults-interceptors.yml. */
  private static final Set<String> DEFAULT_RULE_NAMES = Set.of(
      "trace-annotation", "servlet", "spring-mvc", "jdbc", "orm", "thread-pool");

  @Test
  @DisplayName("production() loads the bundled defaults-interceptors.yml with 6 rules")
  void productionLoadsDefaults() {
    AgentConfig cfg = AgentConfig.parse(null);
    List<InterceptorRule> rules = ConfigLoader.load(cfg, ConfigLoader.production());

    assertThat(rules).hasSize(6);
    Set<String> names = rules.stream().map(r -> r.name).collect(Collectors.toSet());
    assertThat(names).isEqualTo(DEFAULT_RULE_NAMES);
  }

  @Test
  @DisplayName("jdbc rule carries two advice entries (prepare + execute)")
  void jdbcHasTwoAdviceRules() {
    AgentConfig cfg = AgentConfig.parse(null);
    List<InterceptorRule> rules = ConfigLoader.load(cfg, ConfigLoader.production());

    InterceptorRule jdbc = rules.stream().filter(r -> "jdbc".equals(r.name)).findFirst().orElseThrow();
    assertThat(jdbc.adviceRules).hasSize(2);
  }

  @Test
  @DisplayName("every default rule compiles to a non-null Compiled with a type matcher")
  void allRulesCompile() {
    AgentConfig cfg = AgentConfig.parse(null);
    List<InterceptorRule> rules = ConfigLoader.load(cfg, ConfigLoader.production());
    MatcherCompiler compiler = new MatcherCompiler();

    for (InterceptorRule rule : rules) {
      MatcherCompiler.Compiled compiled = compiler.compile(rule);
      assertThat(compiled).isNotNull();
      assertThat(compiled.name()).isEqualTo(rule.name);
      assertThat(compiled.typeMatcher()).isNotNull();
    }
  }

  @Test
  @DisplayName("thread-pool rule matches ThreadPoolExecutor but not Runnable")
  void threadPoolRuleMatches() {
    AgentConfig cfg = AgentConfig.parse(null);
    List<InterceptorRule> rules = ConfigLoader.load(cfg, ConfigLoader.production());
    InterceptorRule threadPool = rules.stream()
        .filter(r -> "thread-pool".equals(r.name)).findFirst().orElseThrow();

    MatcherCompiler.Compiled compiled = new MatcherCompiler().compile(threadPool);
    TypeDescription tpe = TypeDescription.ForLoadedType.of(java.util.concurrent.ThreadPoolExecutor.class);
    TypeDescription runnable = TypeDescription.ForLoadedType.of(Runnable.class);

    assertThat(compiled.typeMatcher().matches(tpe))
        .as("ThreadPoolExecutor should match the thread-pool rule").isTrue();
    assertThat(compiled.typeMatcher().matches(runnable))
        .as("Runnable interface should be excluded and not match").isFalse();
  }

  @Test
  @DisplayName("a rule with null typeMatcher is rejected with an IllegalArgumentException naming the rule")
  void nullTypeMatcherRejected() {
    InterceptorRule rule = new InterceptorRule();
    rule.name = "broken";
    rule.typeMatcher = null;
    rule.adviceRules = List.of();

    MatcherCompiler compiler = new MatcherCompiler();
    assertThatThrownBy(() -> compiler.compile(rule))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("broken");
  }
}
