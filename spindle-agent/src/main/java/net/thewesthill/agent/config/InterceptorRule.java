package net.thewesthill.agent.config;

import java.util.List;

/**
 * SnakeYAML binding model for one interceptor rule in the agent's configuration YAML.
 * <p>
 * Instances are populated by SnakeYAML from the YAML {@code interceptors} list (see
 * {@link ConfigFile}); fields are public and mutable for that reason. Each rule selects a set of
 * types via {@link #typeMatcher} and applies one or more advice rules ({@link #adviceRules}) to
 * matching methods on those types. A rule is compiled into ByteBuddy matchers and a transformer by
 * {@link MatcherCompiler}, where a rule that fails to compile is logged and skipped.
 * <p>
 * Type-matching criteria within {@link TypeMatcherDef} are combined with AND (all must match), with
 * the {@link TypeMatcherDef#anyOf} group OR-combined internally then AND-combined with the rest.
 * Method-matching criteria within {@link MethodMatcherDef} are likewise combined with AND.
 *
 * @author Jonah Fan
 * @since 1.0.0
 */
public final class InterceptorRule {

  /** Human-readable rule name; used in log messages (e.g. "skip rule [name]"). */
  public String name;
  /** Default span kind applied to the rule's advice rules; overridden by an {@link AdviceRule#spanKind}. */
  public String spanKind;
  /** The type selection criteria; a rule with no valid type matcher is rejected at compile time. */
  public TypeMatcherDef typeMatcher;
  /** The advice rules to apply to matching methods on the selected types. */
  public List<AdviceRule> adviceRules;

  /**
   * Returns a short diagnostic representation of the rule.
   *
   * @return the rule's name, span kind, and advice rules
   */
  @Override
  public String toString() {
    return "InterceptorRule{" + name + ", spanKind=" + spanKind + ", rules=" + adviceRules + "}";
  }

  /**
   * YAML binding model for the type-selection criteria of a rule.
   * <p>
   * All non-null criteria are combined with AND: a type must satisfy every populated criterion.
   * {@link #anyOf} is an OR group (compiled recursively, then AND-combined with the other criteria).
   */
  public static final class TypeMatcherDef {

    /** Matches a type whose fully-qualified name equals this value. */
    public String named;
    /** Matches a type whose name ends with this suffix (e.g. {@code ".HttpServlet"}). */
    public String nameEndsWith;
    /** Matches a type that declares a method satisfying this {@link MethodMatcherDef}. */
    public MethodMatcherDef declaresMethod;
    /** Matches a type assignable to any of these super type names (OR). */
    public List<String> anyOfSuperTypes;
    /** OR group of nested {@link TypeMatcherDef}s; a type matching any member satisfies this group. */
    public List<TypeMatcherDef> anyOf;
  }

  /**
   * YAML binding model for the method-selection criteria of an {@link AdviceRule}.
   * <p>
   * All non-null criteria are combined with AND: a method must satisfy every populated criterion.
   */
  public static final class MethodMatcherDef {

    /** Matches a method whose name equals this value. */
    public String named;
    /** Matches a method whose name equals any of these values (OR). */
    public List<String> anyOfNames;
    /** Matches a method annotated with the annotation of this fully-qualified name. */
    public String isAnnotatedWith;
    /** Matches a method annotated with any of these annotation names (OR). */
    public List<String> anyOfAnnotations;
    /** Matches a method that takes exactly this many arguments. */
    public Integer takesArguments;
    /** Index of the argument inspected by {@link #takesArgumentType}/{@link #takesArgumentNameEndsWith}. */
    public Integer takesArgumentAt;
    /** Matches when the argument at {@link #takesArgumentAt} has this fully-qualified type name. */
    public String takesArgumentType;
    /** Matches when the argument at {@link #takesArgumentAt} has a name ending with this suffix. */
    public String takesArgumentNameEndsWith;
  }

  /**
   * YAML binding model for one advice binding: which methods (via {@link #methodMatcher}) to weave
   * which {@link #advice} class into, and the {@link #spanKind} recorded for those spans.
   */
  public static final class AdviceRule {

    /** The method-selection criteria; a rule with no valid method matcher is skipped at compile time. */
    public MethodMatcherDef methodMatcher;
    /** Fully-qualified name of the ByteBuddy advice class to weave. */
    public String advice;
    /** Span kind for this advice; overrides the rule-level {@link InterceptorRule#spanKind}. */
    public String spanKind;
  }

  /**
   * YAML binding model for the root of an interceptor-config file.
   * <p>
   * The top-level document maps to this type as a single list of {@link #interceptors}.
   */
  public static final class ConfigFile {

    /** The list of interceptor rules parsed from the {@code interceptors} key. */
    public List<InterceptorRule> interceptors;
  }
}
