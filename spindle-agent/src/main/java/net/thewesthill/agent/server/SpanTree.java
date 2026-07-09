package net.thewesthill.agent.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import net.thewesthill.agent.collector.SpanEvent;

/**
 * Indexes a flat list of spans into a parent/child tree for hierarchical rendering.
 * <p>
 * Built once via {@link #build} from a trace's spans. A span is treated as a root when its parent
 * id is {@code null} or references a span absent from the set; all other spans are grouped under
 * their parent id. Lookup of children is by span id via {@link #childrenOf}.
 *
 * @author Jonah Fan
 * @since 1.0.0
 */
public final class SpanTree {

  /**
   * Parent id to its child spans.
   */
  private final Map<String, List<SpanEvent>> children = new HashMap<>();
  /**
   * Spans whose parent is missing or absent from the set.
   */
  private final List<SpanEvent> roots = new ArrayList<>();

  /**
   * Builds a span tree from a flat span list. A span is a root when its parent id is {@code null}
   * or not present among the spans' ids; otherwise it is recorded as a child of its parent.
   *
   * @param spans the flat span list for one trace
   * @return a populated tree; never {@code null}
   */
  public static SpanTree build(List<SpanEvent> spans) {
    SpanTree tree = new SpanTree();
    Set<String> ids = spans.stream().map(SpanEvent::spanId).collect(Collectors.toSet());

    for (SpanEvent s : spans) {
      String pid = s.parentId();
      if (pid == null || !ids.contains(pid)) {
        tree.roots.add(s);
      } else {
        tree.children.computeIfAbsent(pid, k -> new ArrayList<>()).add(s);
      }
    }
    return tree;
  }

  /**
   * Returns the root spans (those with no in-set parent).
   *
   * @return the root spans
   */
  public List<SpanEvent> roots() {
    return roots;
  }

  /**
   * Returns the direct children of the given span id, or an empty list when it has none.
   *
   * @param spanId the parent span id
   * @return the children, never {@code null}
   */
  public List<SpanEvent> childrenOf(String spanId) {
    return children.getOrDefault(spanId, List.of());
  }
}