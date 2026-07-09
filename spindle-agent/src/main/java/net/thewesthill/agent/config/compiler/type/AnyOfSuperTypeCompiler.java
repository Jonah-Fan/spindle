package net.thewesthill.agent.config.compiler.type;

import static net.bytebuddy.matcher.ElementMatchers.hasSuperType;
import static net.bytebuddy.matcher.ElementMatchers.named;

import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher.Junction;

import net.thewesthill.agent.config.InterceptorRule.TypeMatcherDef;
import net.thewesthill.agent.config.compiler.TypeMatcherCompiler;
import net.thewesthill.agent.config.compiler.util.MatcherUtils;

/**
 * {@link TypeMatcherCompiler} for the {@link TypeMatcherDef#anyOfSuperTypes} criterion: matches a
 * type assignable to any of the configured super-type names (OR-combined).
 * <p>
 * Each name is matched with {@code hasSuperType(named(...))}; the list is OR-folded via
 * {@link MatcherUtils#orAny}. Returns {@code null} when the list is empty or every name folds to
 * nothing, so an unset criterion contributes nothing to the AND-combined result.
 *
 * @author Jonah Fan
 * @since 1.0.0
 */
public final class AnyOfSuperTypeCompiler implements TypeMatcherCompiler {

  /**
   * Builds an OR-combined "has super type" matcher over {@link TypeMatcherDef#anyOfSuperTypes}.
   *
   * @param def the type-matcher definition to read the super-type names from
   * @return the OR-combined matcher, or {@code null} if the list is unset/empty
   */
  @Override
  public Junction<TypeDescription> compile(TypeMatcherDef def) {
    return MatcherUtils.orAny(def.anyOfSuperTypes, fqn -> hasSuperType(named(fqn)));
  }
}
