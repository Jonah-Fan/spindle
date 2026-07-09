package net.thewesthill.agent.config.compiler.type;

import static net.bytebuddy.matcher.ElementMatchers.nameEndsWith;

import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher.Junction;

import net.thewesthill.agent.config.InterceptorRule.TypeMatcherDef;
import net.thewesthill.agent.config.compiler.TypeMatcherCompiler;

/**
 * {@link TypeMatcherCompiler} for the {@link TypeMatcherDef#nameEndsWith} criterion: matches a type
 * whose fully-qualified name ends with the configured suffix (e.g. {@code ".HttpServlet"}), used to
 * match servlet subclasses regardless of package.
 *
 * @author Jonah Fan
 * @since 1.0.0
 */
public final class NameEndsWithTypeCompiler implements TypeMatcherCompiler {

  /**
   * Builds a name-suffix matcher, or returns {@code null} when
   * {@link TypeMatcherDef#nameEndsWith} is unset.
   *
   * @param def the type-matcher definition to read the suffix from
   * @return a {@code nameEndsWith(...)} matcher, or {@code null} if the criterion is unset
   */
  @Override
  public Junction<TypeDescription> compile(TypeMatcherDef def) {
    return def.nameEndsWith != null ? nameEndsWith(def.nameEndsWith) : null;
  }
}
