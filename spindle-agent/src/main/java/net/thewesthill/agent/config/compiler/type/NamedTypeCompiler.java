package net.thewesthill.agent.config.compiler.type;

import static net.bytebuddy.matcher.ElementMatchers.named;

import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher.Junction;

import net.thewesthill.agent.config.InterceptorRule.TypeMatcherDef;
import net.thewesthill.agent.config.compiler.TypeMatcherCompiler;

/**
 * {@link TypeMatcherCompiler} for the {@link TypeMatcherDef#named} criterion: matches a type whose
 * fully-qualified name equals the configured value.
 *
 * @author Jonah Fan
 * @since 1.0.0
 */
public final class NamedTypeCompiler implements TypeMatcherCompiler {

  /**
   * Builds an exact-name matcher, or returns {@code null} when {@link TypeMatcherDef#named} is unset.
   *
   * @param def the type-matcher definition to read the name from
   * @return a {@code named(...)} matcher, or {@code null} if the criterion is unset
   */
  @Override
  public Junction<TypeDescription> compile(TypeMatcherDef def) {
    return def.named != null ? named(def.named) : null;
  }
}
