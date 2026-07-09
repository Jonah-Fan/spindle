package net.thewesthill.agent.config;

import java.util.List;

import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher.Junction;

import net.thewesthill.agent.config.InterceptorRule.TypeMatcherDef;
import net.thewesthill.agent.config.compiler.MethodMatcherCompiler;
import net.thewesthill.agent.config.compiler.TypeMatcherCompiler;
import net.thewesthill.agent.config.compiler.type.AnyOfSuperTypeCompiler;
import net.thewesthill.agent.config.compiler.type.DeclaresMethodTypeCompiler;
import net.thewesthill.agent.config.compiler.type.NameEndsWithTypeCompiler;
import net.thewesthill.agent.config.compiler.type.NamedTypeCompiler;
import net.thewesthill.agent.config.compiler.util.MatcherUtils;

/**
 * Compiles a {@link TypeMatcherDef} into a ByteBuddy type matcher by AND-combining the outputs of a
 * fixed set of {@link TypeMatcherCompiler}s.
 * <p>
 * Used by {@link MatcherCompiler#compile} to build the type-selection half of a rule. The registered
 * compilers cover, in order: exact-name ({@link NamedTypeCompiler}), name-suffix
 * ({@link NameEndsWithTypeCompiler}), assignable-to-super-type ({@link AnyOfSuperTypeCompiler}),
 * and declares-method ({@link DeclaresMethodTypeCompiler}). Each compiler compiles the matching
 * criterion it understands (returning {@code null} when the criterion is unset); the results are
 * combined with {@link MatcherUtils#and}, which treats {@code null} as the identity, so unset
 * criteria are simply skipped rather than voiding the matcher.
 * <p>
 * The {@link TypeMatcherDef#anyOf} OR group is compiled recursively via
 * {@link MatcherUtils#orAny} and then AND-combined with the rest.
 *
 * @author Jonah Fan
 * @since 1.0.0
 * @see MatcherCompiler
 */
public final class MatcherRegistry {

  /** The ordered type-matcher compilers applied to each definition. */
  private final List<TypeMatcherCompiler> typeCompilers;

  /**
   * Creates a registry holding the four built-in type-matcher compilers. The declares-method
   * compiler is given {@code methodCompiler} so it can evaluate a method matcher against a type's
   * declared methods.
   *
   * @param methodCompiler the method-matcher compiler used by {@link DeclaresMethodTypeCompiler}
   */
  public MatcherRegistry(MethodMatcherCompiler methodCompiler) {
    this.typeCompilers =
        List.of(
            new NamedTypeCompiler(),
            new NameEndsWithTypeCompiler(),
            new AnyOfSuperTypeCompiler(),
            new DeclaresMethodTypeCompiler(methodCompiler));
  }

  /**
   * Compiles a type-matcher definition into a combined {@link Junction}, or returns {@code null}
   * when no criterion is set.
   * <p>
   * Each registered {@link TypeMatcherCompiler} contributes its matching criterion (or {@code null}
   * if unset); the contributions are AND-combined via {@link MatcherUtils#and} (null-skipping). If a
   * non-empty {@link TypeMatcherDef#anyOf} group is present, each sub-definition is compiled
   * recursively and OR-combined via {@link MatcherUtils#orAny}, then AND-combined with the rest.
   *
   * @param def the type-matcher definition, or {@code null}
   * @return the combined type matcher, or {@code null} if {@code def} is {@code null} or sets no
   *         criterion
   */
  public Junction<TypeDescription> compileType(TypeMatcherDef def) {
    if (def == null) {
      return null;
    }

    Junction<TypeDescription> result = null;

    for (TypeMatcherCompiler compiler : typeCompilers) {
      Junction<TypeDescription> part = compiler.compile(def);
      result = MatcherUtils.and(result, part);
    }

    // Handle nested anyOf (OR) group: compile each sub-definition recursively and combine with OR,
    // then combine the group with existing AND conditions.
    if (def.anyOf != null && !def.anyOf.isEmpty()) {
      Junction<TypeDescription> anyOfMatcher =
          MatcherUtils.orAny(def.anyOf, this::compileType);
      result = MatcherUtils.and(result, anyOfMatcher);
    }

    return result;
  }
}
