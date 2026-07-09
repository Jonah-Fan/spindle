package net.thewesthill.agent.advice;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an advice method parameter to receive the span kind bound per interceptor rule.
 * <p>
 * A marker-only parameter annotation (no members). It is not read by bytecode at runtime; instead
 * {@link net.thewesthill.agent.config.MatcherCompiler} binds it through ByteBuddy's custom advice
 * mapping:
 * <pre>{@code
 * Advice.withCustomMapping().bind(SpanKind.class, spanKind)
 * }</pre>
 * so the rule-configured {@code spanKind} string (e.g. {@code "JDBC"}, {@code "HTTP"}) is injected
 * into the annotated {@code String} parameter of the advice at instrumentation time. This lets a
 * single generic advice such as {@link MethodAdvice} record different span kinds per rule without
 * hardcoding them.
 * <p>
 * Retained at runtime and applicable only to parameters.
 *
 * @author Jonah Fan
 * @see MethodAdvice
 * @since 1.0.0
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface SpanKind {

}
