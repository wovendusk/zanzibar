package dev.zanzibar.leopard;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the incrementally-maintained transitive closure, including the hard cases:
 * deletion (which shrinks closures) and cycles. The core check is differential —
 * after every random edge insert/delete, the incrementally-maintained closure of
 * every node must equal a from-scratch BFS over the current edge set.
 */
class GroupGraphTest {

    @Test
    void closureIncludesSelfAndTransitiveChildren() {
        var g = new GroupGraph();
        g.addEdge(1, 2);
        g.addEdge(2, 3);
        assertEquals(Set.of(1L, 2L, 3L), g.closureOf(1));
        assertEquals(Set.of(2L, 3L), g.closureOf(2));
        assertEquals(Set.of(3L), g.closureOf(3));
    }

    @Test
    void insertPropagatesToAncestors() {
        var g = new GroupGraph();
        g.addEdge(1, 2);   // 1 -> 2
        g.addEdge(3, 1);   // 3 -> 1 -> 2
        assertEquals(Set.of(3L, 1L, 2L), g.closureOf(3));
        g.addEdge(2, 4);   // now everything above 2 gains 4
        assertTrue(g.closureOf(3).contains(4L));
        assertTrue(g.closureOf(1).contains(4L));
    }

    @Test
    void deleteShrinksClosureWhenNoOtherPath() {
        var g = new GroupGraph();
        g.addEdge(1, 2);
        g.addEdge(2, 3);
        assertTrue(g.closureOf(1).contains(3L));
        g.removeEdge(2, 3);
        assertEquals(Set.of(1L, 2L), g.closureOf(1));
        assertEquals(Set.of(2L), g.closureOf(2));
    }

    @Test
    void deleteKeepsClosureWhenAlternatePathExists() {
        var g = new GroupGraph();
        g.addEdge(1, 2);
        g.addEdge(1, 3);
        g.addEdge(2, 3);   // 1 reaches 3 via both 1->3 and 1->2->3
        g.removeEdge(1, 3); // still reachable via 1->2->3
        assertTrue(g.closureOf(1).contains(3L));
    }

    @Test
    void toleratesCycles() {
        var g = new GroupGraph();
        g.addEdge(1, 2);
        g.addEdge(2, 3);
        g.addEdge(3, 1);   // cycle 1->2->3->1
        Set<Long> all = Set.of(1L, 2L, 3L);
        assertEquals(all, g.closureOf(1));
        assertEquals(all, g.closureOf(2));
        assertEquals(all, g.closureOf(3));
        // breaking the cycle shrinks closures correctly
        g.removeEdge(3, 1);
        assertEquals(Set.of(3L), g.closureOf(3));
        assertEquals(Set.of(1L, 2L, 3L), g.closureOf(1));
    }

    @Test
    void incrementalMatchesFromScratch_fuzz() {
        Random rng = new Random(99);
        int nodes = 8;
        for (int seed = 0; seed < 200; seed++) {
            var graph = new GroupGraph();
            // reference edge set
            Set<List<Long>> edges = new HashSet<>();

            for (int op = 0; op < 40; op++) {
                long a = rng.nextInt(nodes);
                long b = rng.nextInt(nodes);
                if (a == b) continue;
                List<Long> e = List.of(a, b);
                if (rng.nextBoolean()) {
                    graph.addEdge(a, b);
                    edges.add(e);
                } else {
                    graph.removeEdge(a, b);
                    edges.remove(e);
                }

                // Verify every node's incremental closure equals a fresh BFS.
                for (long n = 0; n < nodes; n++) {
                    Set<Long> expected = bfs(n, edges);
                    if (graph.knowsGroup(n)) {
                        assertEquals(expected, graph.closureOf(n),
                                "closure mismatch node=" + n + " seed=" + seed + " op=" + op
                                        + " edges=" + edges);
                    }
                }
            }
        }
    }

    private static Set<Long> bfs(long start, Set<List<Long>> edges) {
        Map<Long, List<Long>> adj = new HashMap<>();
        for (List<Long> e : edges) {
            adj.computeIfAbsent(e.get(0), k -> new ArrayList<>()).add(e.get(1));
        }
        Set<Long> seen = new HashSet<>();
        Deque<Long> q = new ArrayDeque<>();
        seen.add(start);
        q.add(start);
        while (!q.isEmpty()) {
            long cur = q.poll();
            for (long nx : adj.getOrDefault(cur, List.of())) {
                if (seen.add(nx)) q.add(nx);
            }
        }
        return seen;
    }
}
