package net.thewesthill.agent.config.compiler.type;

import static net.bytebuddy.matcher.ElementMatchers.declaresMethod;

import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher.Junction;

import net.thewesthill.agent.config.InterceptorRule.TypeMatcherDef;
import net.thewesthill.agent.config.compiler.MethodMatcherCompiler;
import net.thewesthill.agent.config.compiler.TypeMatcherCompiler;

/**
 * {@link TypeMatcherCompiler} for the {@link TypeMatcherDef#declaresMethod} criterion: matches a type
 * that declares at least one method satisfying the nested
 * {@link net.thewesthill.agent.config.InterceptorRule.MethodMatcherDef}.
 * <p>
 * The nested method matcher is compiled via the supplied {@link MethodMatcherCompiler} and wrapped
 * in {@code declaresMethod(...)}. Returns {@code null} when the criterion is unset or the nested
 * matcher compiles to nothing, so it contributes nothing to the AND-combined result.
 * @author Jonah Fan
 * @since 1.0.0
 */
public class DeclaresMethodTypeCompiler implements TypeMatcherCompiler {

  /** The method-matcher compiler used to resolve the nested
   * {@link net.thewesthill.agent.config.InterceptorRule.MethodMatcherDef}. */
  private final MethodMatcherCompiler matcherCompiler;

  /**
   * Creates a declares-method compiler with the given method-matcher compiler.
   *
   * @param matcherCompiler the compiler for the nested method matcher
   */
  public DeclaresMethodTypeCompiler(MethodMatcherCompiler matcherCompiler) {
    this.matcherCompiler = matcherCompiler;
  }

  /**
   * Builds a {@code declaresMethod(...)} matcher from the nested method-matcher definition.
   *
   * @param def the type-matcher definition to read the method criterion from
   * @return a {@code declaresMethod(...)} matcher, or {@code null} if the criterion is unset or the
   *         nested method matcher compiles to {@code null}
   */
  @Override
  public Junction<TypeDescription> compile(TypeMatcherDef def) {
    if (def.declaresMethod == null) {
      return null;
    }

    Junction<MethodDescription> methodMatcher = matcherCompiler.compile(def.declaresMethod);
    if (methodMatcher == null) {
      return null;
    }

    return declaresMethod(methodMatcher);
  }
}
