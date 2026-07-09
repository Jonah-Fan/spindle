package net.thewesthill.agent.config.compiler.util;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import net.bytebuddy.matcher.ElementMatcher.Junction;

/**
 * Helpers for combining ByteBuddy {@link Junction} matchers null-safely.
 * <p>
 * The matchers in this package combine multiple criteria with AND/OR. Because each criterion may be
 * unset (compiled to {@code null}), the standard {@link Junction#and}/{@link Junction#or} cannot be
 * used directly — they would NPE on a null operand. {@link #and} treats {@code null} as the identity
 * and {@link #orAny} skips null elements, so unset criteria are transparently ignored rather than
 * voiding the combined matcher.
 *
 * @author Jonah Fan
 * @since 1.0.0
 */
public final class MatcherUtils {

  /** Private constructor; this class exposes only static methods. */
  private MatcherUtils() {
  }

  /**
   * Null-safe AND of two junctions. Returns the non-null operand when the other is {@code null}
   * (so an unset criterion is the AND-identity), and {@code null} only when both are {@code null}.
   *
   * @param left  the left junction, or {@code null}
   * @param right the right junction, or {@code null}
   * @param <T>   the matched element type
   * @return {@code left.and(right)} when both non-null, the non-null one otherwise, or {@code null}
   *         when both are {@code null}
   */
  public static <T> Junction<T> and(Junction<T> left, Junction<T> right) {
    if (left == null) {
      return right;
    }
    if (right == null) {
      return left;
    }
    return left.and(right);
  }

  /**
   * Reduces a list of items to a single OR-combined junction by mapping each item and OR-folding the
   * non-null results. Returns {@code null} when {@code items} is {@code null}/empty or every mapped
   * result is {@code null}.
   *
   * @param items  the items to map and OR-combine, or {@code null}
   * @param mapper maps each item to a junction (may return {@code null}, which is skipped)
   * @param <T>    the input item type
   * @param <R>    the matched element type
   * @return the OR-combined junction, or {@code null} if there is nothing to combine
   */
  public static <T, R> Junction<R> orAny(List<T> items, Function<T, Junction<R>> mapper) {
    if (items == null || items.isEmpty()) {
      return null;
    }
    return items.stream()
        .map(mapper)
        .filter(Objects::nonNull)
        .reduce(Junction::or)
        .orElse(null);
  }
}
