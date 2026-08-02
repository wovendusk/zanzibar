package dev.zanzibar.leopard;

import dev.zanzibar.config.NamespaceConfig;
import dev.zanzibar.config.RewriteRule;
import dev.zanzibar.engine.CheckEngine;
import dev.zanzibar.model.ObjectRef;
import dev.zanzibar.model.SubjectRef;
import dev.zanzibar.model.Zookie;
import dev.zanzibar.store.InMemoryTupleStore;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Demonstrates the payoff: on a deeply nested group chain, the authoritative check
 * pointer-chases O(depth) levels with a storage read at each, while Leopard answers
 * with a single galloping set-intersection. Same answer, dramatically less work.
 */
class LeopardBenchmarkTest {

    private static final int DEPTH = 1500;

    @Test
    void flattenedIndexBeatsRecursiveWalkOnDeepNesting() {
        var store = new InMemoryTupleStore();
        var engine = new CheckEngine(store,
                Map.of("group", NamespaceConfig.builder("group")
                        .relation("member", RewriteRule.thisRelation()).build()));
        var index = new LeopardIndex("group", "member");

        // Build g_{i+1} contains g_i, for a chain g0 ⊂ g1 ⊂ ... ⊂ g_DEPTH; u0 ∈ g0.
        SubjectRef u0 = SubjectRef.user("user", "u0");
        long rev = store.write(new ObjectRef("group", "g0"), "member", u0).revision();
        index.applyWrite(new ObjectRef("group", "g0"), "member", u0, rev);
        for (int i = 0; i < DEPTH; i++) {
            ObjectRef parent = new ObjectRef("group", "g" + (i + 1));
            SubjectRef childSet = SubjectRef.userset("group", "g" + i, "member");
            rev = store.write(parent, "member", childSet).revision();
            index.applyWrite(parent, "member", childSet, rev);
        }

        ObjectRef top = new ObjectRef("group", "g" + DEPTH);
        Zookie now = new Zookie(store.latestRevision());

        // Correctness first: both agree u0 is a member of the top group.
        assertTrue(engine.check(top, "member", u0, now));
        assertTrue(index.isMember(u0, top));
        assertEquals(DEPTH + 1, index.closureSize(top), "closure spans the whole chain");

        // Warm up.
        for (int i = 0; i < 20; i++) {
            engine.check(top, "member", u0, now);
            index.isMember(u0, top);
        }

        int iters = 200;
        long engineNanos = 0, leopardNanos = 0;
        for (int i = 0; i < iters; i++) {
            long t0 = System.nanoTime();
            boolean e = engine.check(top, "member", u0, now);
            long t1 = System.nanoTime();
            boolean l = index.isMember(u0, top);
            long t2 = System.nanoTime();
            engineNanos += (t1 - t0);
            leopardNanos += (t2 - t1);
            assertTrue(e && l);
        }

        double speedup = (double) engineNanos / Math.max(1, leopardNanos);
        System.out.printf(
                "Deep-nesting check (depth %d): recursive engine %.1f µs/op, Leopard %.2f µs/op → %.0f× faster%n",
                DEPTH,
                engineNanos / 1000.0 / iters,
                leopardNanos / 1000.0 / iters,
                speedup);

        // A conservative floor — the real gap is typically two orders of magnitude.
        assertTrue(leopardNanos * 2 < engineNanos,
                "Leopard should be dramatically faster on deep nesting (speedup=" + speedup + ")");
    }
}
