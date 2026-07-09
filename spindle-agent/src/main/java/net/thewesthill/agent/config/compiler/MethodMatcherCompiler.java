package net.thewesthill.agent.config.compiler;

import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.matcher.ElementMatcher.Junction;

import net.thewesthill.agent.config.InterceptorRule.MethodMatcherDef;

/**
 * Strategy that compiles a {@link MethodMatcherDef} into a ByteBuddy method matcher.
 * <p>
 * Implemented by {@link DefaultMethodMatcherCompiler} and used by
 * {@link net.thewesthill.agent.config.MatcherCompiler} (for each advice entry's method matcher) and by
 * {@link net.thewesthill.agent.config.compiler.type.DeclaresMethodTypeCompiler} (to evaluate a method
 * matcher against a type's declared methods). Returns {@code null} when the definition sets no
 * criterion, so callers can skip it when combining matchers.
 *
 * @author Jonah Fan
 * @since 1.0.0
 * @see DefaultMethodMatcherCompiler
 */
@FunctionalInterface
public interface MethodMatcherCompiler {

  /**
   * Compiles the method-matcher definition into a {@link Junction}.
   *
   * @param def the method-matcher definition, or {@code null}
   * @return the compiled method matcher, or {@code null} if {@code def} is {@code null} or sets no
   *         criterion
   */
  Junction<MethodDescription> compile(MethodMatcherDef def);
}
