package net.thewesthill.agent.config.compiler;

import static net.bytebuddy.matcher.ElementMatchers.isAnnotatedWith;
import static net.bytebuddy.matcher.ElementMatchers.nameEndsWith;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.matcher.ElementMatcher.Junction;
import net.bytebuddy.matcher.ElementMatchers;

import net.thewesthill.agent.config.InterceptorRule.MethodMatcherDef;
import net.thewesthill.agent.config.compiler.util.MatcherUtils;

/**
 * Default {@link MethodMatcherCompiler} that AND-combines every populated criterion of a
 * {@link MethodMatcherDef} into one method matcher.
 * <p>
 * Each criterion is compiled to a {@link Junction} (or {@code null} when unset) and folded together
 * with {@link MatcherUtils#and}, which treats {@code null} as the AND-identity — so unset criteria
 * are skipped rather than voiding the result. The {@link MethodMatcherDef#anyOfNames} and
 * {@link MethodMatcherDef#anyOfAnnotations} lists are OR-combined internally via
 * {@link MatcherUtils#orAny} before being AND-folded. Used by
 * {@link net.thewesthill.agent.config.MatcherCompiler} for each advice entry's method matcher and by
 * {@link net.thewesthill.agent.config.compiler.type.DeclaresMethodTypeCompiler} to evaluate a
 * "declares-method" type criterion.
 *
 * @author Jonah Fan
 * @since 1.0.0
 * @see MethodMatcherCompiler
 */
public class DefaultMethodMatcherCompiler implements MethodMatcherCompiler {

  /**
   * Compiles the definition by AND-folding each populated criterion.
   *
   * @param def the method-matcher definition, or {@code null}
   * @return the combined method matcher, or {@code null} if {@code def} is {@code null} or sets no
   *         criterion
   */
  @Override
  public Junction<MethodDescription> compile(MethodMatcherDef def) {
    if (def == null) {
      return null;
    }

    Junction<MethodDescription> result = null;

    // named
    if (def.named != null) {
      result = MatcherUtils.and(result, named(def.named));
    }

    // anyOfNames
    if (def.anyOfNames != null && !def.anyOfNames.isEmpty()) {
      Junction<MethodDescription> anyOfNames =
          MatcherUtils.orAny(def.anyOfNames, ElementMatchers::named);
      result = MatcherUtils.and(result, anyOfNames);
    }

    // isAnnotatedWith
    if (def.isAnnotatedWith != null) {
      result = MatcherUtils.and(result, isAnnotatedWith(named(def.isAnnotatedWith)));
    }

    // anyOfAnnotations
    if (def.anyOfAnnotations != null && !def.anyOfAnnotations.isEmpty()) {
      Junction<MethodDescription> anyOfAnnotations =
          MatcherUtils.orAny(def.anyOfAnnotations, a -> isAnnotatedWith(named(a)));
      result = MatcherUtils.and(result, anyOfAnnotations);
    }

    // takesArguments
    if (def.takesArguments != null) {
      result = MatcherUtils.and(result, takesArguments(def.takesArguments));
    }

    // takesArgumentsAt takesArgumentType / takesArgumentNameEndsWith
    if (def.takesArgumentAt != null) {
      if (def.takesArgumentType != null) {
        result =
            MatcherUtils.and(
                result, takesArgument(def.takesArgumentAt, named(def.takesArgumentType)));
      }

      if (def.takesArgumentNameEndsWith != null) {
        result =
            MatcherUtils.and(
                result,
                takesArgument(def.takesArgumentAt, nameEndsWith(def.takesArgumentNameEndsWith)));
      }
    }

    return result;
  }
}
