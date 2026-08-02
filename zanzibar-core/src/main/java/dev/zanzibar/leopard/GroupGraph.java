package dev.zanzibar.leopard;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The group-containment graph with an incrementally maintained transitive closure.
 *
 * <p>An edge {@code parent -> child} means "every member of {@code child} is also a
 * member of {@code parent}" (child is nested under parent). For each group we keep
 * {@code closure(g)} = the set of groups whose membership rolls up into {@code g},
 * including {@code g} itself. A user is a transitive member of {@code g} iff one of
 * the groups they directly belong to is in {@code closure(g)}.
 *
 * <p>Maintenance is genuinely incremental, not recompute-everything:
 * <ul>
 *   <li><b>Insert</b> {@code parent -> child}: closures only grow, and only for
 *       {@code parent} and everything that can reach it. We add {@code closure(child)}
 *       to each of those ancestors in one forward pass.</li>
 *   <li><b>Delete</b> {@code parent -> child}: closures can only shrink, and only for
 *       groups that could reach the edge. We recompute exactly those from scratch —
 *       correct incremental deletion of a transitive closure is otherwise very hard.</li>
 * </ul>
 *
 * <p>Cycles are tolerated: mutually-nested groups simply end up with equal closures.
 * Every mutating method returns the set of groups whose closure changed, so the
 * caller can invalidate exactly those materialized snapshots.
 */
final class GroupGraph {

    private final Map<Long, Set<Long>> adj = new HashMap<>();      // parent -> direct children
    private final Map<Long, Set<Long>> radj = new HashMap<>();     // child  -> direct parents
    private final Map<Long, Set<Long>> closure = new HashMap<>();  // group  -> transitive members incl self

    private static final Set<Long> NONE = Set.of();

    private void ensureNode(long g) {
        adj.computeIfAbsent(g, k -> new HashSet<>());
        radj.computeIfAbsent(g, k -> new HashSet<>());
        closure.computeIfAbsent(g, k -> {
            Set<Long> self = new HashSet<>();
            self.add(g);
            return self;
        });
    }

    boolean knowsGroup(long g) {
        return closure.containsKey(g);
    }

    /** The transitive closure of {@code g} (incl {@code g}); {@code {g}} if unknown. */
    Set<Long> closureOf(long g) {
        Set<Long> c = closure.get(g);
        return c != null ? c : Set.of(g);
    }

    /**
     * Add {@code parent -> child}. Returns the groups whose closure grew (empty if
     * the edge already existed).
     */
    Set<Long> addEdge(long parent, long child) {
        ensureNode(parent);
        ensureNode(child);
        if (!adj.get(parent).add(child)) {
            return NONE; // edge already present — closures unchanged
        }
        radj.get(child).add(parent);

        // Snapshot child's closure first: we're about to mutate ancestor closures,
        // and in a cycle `parent` itself may be among the ancestors.
        Set<Long> childClosure = new HashSet<>(closureOf(child));

        Set<Long> affected = ancestorsInclusive(parent);
        for (long a : affected) {
            closure.get(a).addAll(childClosure);
        }
        return affected;
    }

    /**
     * Remove {@code parent -> child}. Returns the groups whose closure was
     * recomputed (empty if the edge did not exist).
     */
    Set<Long> removeEdge(long parent, long child) {
        Set<Long> children = adj.get(parent);
        if (children == null || !children.contains(child)) {
            return NONE;
        }
        // Everyone who could reach `parent` may have depended on this edge.
        // Capture them BEFORE mutating, then recompute their closures afterwards.
        Set<Long> affected = ancestorsInclusive(parent);

        children.remove(child);
        radj.get(child).remove(parent);

        for (long a : affected) {
            closure.put(a, bfsReachable(a, adj));
        }
        return affected;
    }

    /** All groups that can reach {@code start} following containment edges, incl {@code start}. */
    private Set<Long> ancestorsInclusive(long start) {
        return bfsReachable(start, radj);
    }

    /** Standard BFS reachable-set (incl start) over the given adjacency map. */
    private Set<Long> bfsReachable(long start, Map<Long, Set<Long>> edges) {
        Set<Long> seen = new HashSet<>();
        Deque<Long> queue = new ArrayDeque<>();
        seen.add(start);
        queue.add(start);
        while (!queue.isEmpty()) {
            long cur = queue.poll();
            for (long next : edges.getOrDefault(cur, NONE)) {
                if (seen.add(next)) {
                    queue.add(next);
                }
            }
        }
        return seen;
    }
}
