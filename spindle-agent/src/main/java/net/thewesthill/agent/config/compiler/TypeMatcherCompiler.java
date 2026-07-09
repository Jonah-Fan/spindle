package net.thewesthill.agent.config.compiler;

import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher.Junction;

import net.thewesthill.agent.config.InterceptorRule.TypeMatcherDef;

/**
 * Strategy that compiles one criterion of a {@link TypeMatcherDef} into a ByteBuddy type matcher.
 * <p>
 * Each implementation understands a single matching criterion (exact name, name suffix, super type,
 * or declares-method) and returns {@code null} when that criterion is unset. The
 * {@link net.thewesthill.agent.config.MatcherRegistry} registers several and AND-combines their outputs,
 * so an individual compiler contributes only the criterion it owns.
 *
 * @author Jonah Fan
 * @since 1.0.0
 * @see net.thewesthill.agent.config.MatcherRegistry
 */
@FunctionalInterface
public interface TypeMatcherCompiler {

  /**
   * Compiles this compiler's criterion from the definition into a {@link Junction}.
   *
   * @param def the type-matcher definition to read this compiler's criterion from, or {@code null}
   * @return the compiled type matcher for this criterion, or {@code null} if the criterion is unset
   *         (or {@code def} is {@code null})
   */
  Junction<TypeDescription> compile(TypeMatcherDef def);
}
