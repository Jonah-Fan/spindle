package net.thewesthill.agent.config;

import static net.bytebuddy.matcher.ElementMatchers.isInterface;
import static net.bytebuddy.matcher.ElementMatchers.not;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.AgentBuilder.LocationStrategy;
import net.bytebuddy.agent.builder.AgentBuilder.Transformer.ForAdvice;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.matcher.ElementMatcher.Junction;

import net.thewesthill.agent.advice.SpanKind;
import net.thewesthill.agent.config.InterceptorRule.AdviceRule;
import net.thewesthill.agent.config.compiler.DefaultMethodMatcherCompiler;
import net.thewesthill.agent.config.compiler.MethodMatcherCompiler;
import net.thewesthill.agent.config.compiler.util.MatcherUtils;
import net.thewesthill.agent.log.AgentLog;

/**
 * Compiles a single {@link InterceptorRule} into a ByteBuddy type matcher plus advice transformer.
 * <p>
 * The entry point is {@link #compile}, used by {@link net.thewesthill.agent.builder.AgentBuilderProvider#build}
 * to turn each loaded rule into a {@link Compiled} value. It delegates type-matching to {@link MatcherRegistry} and
 * method-matching to a {@link MethodMatcherCompiler} (default implementation
 * {@link DefaultMethodMatcherCompiler}), then builds a {@link ForAdvice} transformer that weaves the
 * configured advice classes into the matched methods.
 * <p>
 * Two compile-time invariants:
 * <ul>
 *   <li>A rule with no valid type matcher is rejected ({@link IllegalArgumentException}); a rule with
 *       an advice entry lacking a method matcher is logged and that entry skipped, but the rule
 *       still compiles with its remaining advice.</li>
 *   <li>Interfaces are excluded from the type matcher — see {@link #compile} for the JVMTI reason.</li>
 * </ul>
 * Span kinds are bound per advice entry via ByteBuddy's custom mapping so a generic advice can vary
 * its kind per rule (see {@link SpanKind}).
 *
 * @author Jonah Fan
 * @since 1.0.0
 * @see net.thewesthill.agent.builder.AgentBuilderProvider
 */
public final class MatcherCompiler {

  /** Registry of type-matcher compilers used to build the rule's type matcher. */
  private final MatcherRegistry registry;
  /** Method-matcher compiler used to build each advice entry's method matcher. */
  private final MethodMatcherCompiler compiler;
  /**
   * Class-file location strategy pointing at this compiler's classloader, so ByteBuddy can locate the
   * advice classes referenced by name (e.g. {@code net.thewesthill.agent.advice.MethodAdvice}) when
   * weaving. Reused across all advice entries of a rule.
   */
  private final LocationStrategy agentLocation =
      new LocationStrategy.Simple(
          ClassFileLocator.ForClassLoader.of(MatcherCompiler.class.getClassLoader()));

  /**
   * Creates a compiler with the default {@link DefaultMethodMatcherCompiler} and a fresh
   * {@link MatcherRegistry}.
   */
  public MatcherCompiler() {
    MethodMatcherCompiler methodCompiler = new DefaultMethodMatcherCompiler();
    this.compiler = methodCompiler;
    this.registry = new MatcherRegistry(methodCompiler);
  }

  /**
   * Compiles one rule into a {@link Compiled} value: a type matcher (with interfaces excluded) and
   * an advice transformer.
   * <p>
   * The type matcher comes from {@link MatcherRegistry#compileType}; a {@code null} result is treated
   * as a rule error. Interfaces are then excluded with {@code not(isInterface())}: JVMTI retransform
   * disallows adding methods, and advice weaving adds methods, so an interface entering a retransform
   * batch would fail and abort the whole batch (including unrelated classes such as
   * {@code ThreadPoolExecutor}). Concrete implementations are unaffected and still match.
   * <p>
   * The transformer weaves each {@link AdviceRule} (whose method matcher is valid) into the type,
   * binding the advice entry's span kind (or the rule-level default) via {@link SpanKind}. Advice
   * entries with no method matcher are logged and skipped.
   *
   * @param rule the rule to compile
   * @return the compiled type matcher and transformer
   * @throws IllegalArgumentException if the rule has no valid type matcher
   */
  public Compiled compile(InterceptorRule rule) {
    Junction<TypeDescription> typeMatcher = registry.compileType(rule.typeMatcher);
    if (typeMatcher == null) {
      throw new IllegalArgumentException("rule [" + rule.name + "] has no valid typeMatcher");
    }
    // Interfaces cannot be retransformed: advice weaving adds methods, which JVMTI
    // retransform disallows. Excluding interfaces prevents a JDK interface (e.g.
    // java.sql.Connection) from entering a retransform batch and failing, which would
    // also abort other classes in the same batch (e.g. ThreadPoolExecutor). Concrete
    // implementations are unaffected — they are not interfaces and still match.
    typeMatcher = MatcherUtils.and(typeMatcher, not(isInterface()));
    ForAdvice transformer = buildTransformer(rule);
    return new Compiled(rule.name, typeMatcher, transformer);
  }

  /**
   * Builds the advice transformer for a rule by folding each valid {@link AdviceRule} into the
   * transformer via {@link #createAdvice}.
   * <p>
   * An advice entry whose method matcher compiles to {@code null} is logged and skipped; the
   * remaining entries are still applied. Each entry's span kind defaults to the rule-level
   * {@link InterceptorRule#spanKind} when the entry does not set its own.
   *
   * @param rule the rule whose advice entries to bind
   * @return a transformer weaving the rule's valid advice entries
   */
  private ForAdvice buildTransformer(InterceptorRule rule) {
    ForAdvice base = new ForAdvice().with(agentLocation);
    for (AdviceRule ar : rule.adviceRules) {
      Junction<MethodDescription> mm = compileMethod(ar.methodMatcher);
      if (mm == null) {
        AgentLog.warn("rule [" + rule.name + "] advice has no methodMatcher, skipped");
        continue;
      }
      String spanKind = ar.spanKind != null ? ar.spanKind : rule.spanKind;
      base = createAdvice(base, mm, ar.advice, spanKind);
    }
    return base;
  }

  /**
   * Compiles a method-matcher definition via the configured {@link MethodMatcherCompiler}.
   *
   * @param def the method-matcher definition
   * @return the compiled method matcher, or {@code null} if {@code def} is {@code null}
   */
  private Junction<MethodDescription> compileMethod(InterceptorRule.MethodMatcherDef def) {
    return compiler.compile(def);
  }

  /**
   * Binds one advice class to a method matcher on the transformer.
   * <p>
   * When {@code spanKind} is non-null it is bound through ByteBuddy's custom advice mapping
   * ({@link Advice.WithCustomMapping#bind}) so the {@link SpanKind}-annotated parameter of the
   * advice receives it; otherwise the advice is bound with the default mapping.
   *
   * @param base          the transformer to extend
   * @param methodMatcher the method matcher this advice applies to
   * @param adviceClass   the fully-qualified advice class name
   * @param spanKind       the span kind to bind, or {@code null} for the default mapping
   * @return the extended transformer
   */
  private ForAdvice createAdvice(
      ForAdvice base,
      Junction<MethodDescription> methodMatcher,
      String adviceClass,
      String spanKind) {
    if (spanKind == null) {
      return base.advice(methodMatcher, adviceClass);
    } else {
      return new ForAdvice(Advice.withCustomMapping().bind(SpanKind.class, spanKind))
          .with(agentLocation)
          .advice(methodMatcher, adviceClass);
    }
  }

  /**
   * The compiled form of a rule: its name, the ByteBuddy type matcher, and the advice transformer
   * to apply to matching types.
   *
   * @param name        the rule name (for logging/diagnostics)
   * @param typeMatcher the type matcher (interfaces already excluded)
   * @param transformer the advice transformer
   */
  public record Compiled(
      String name, Junction<TypeDescription> typeMatcher, AgentBuilder.Transformer transformer) {

  }
}
