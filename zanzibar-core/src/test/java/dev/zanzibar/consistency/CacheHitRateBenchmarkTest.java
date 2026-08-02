package dev.zanzibar.consistency;

import dev.zanzibar.cache.CheckCache;
import dev.zanzibar.engine.CheckEngine;
import dev.zanzibar.model.ObjectRef;
import dev.zanzibar.model.SubjectRef;
import dev.zanzibar.model.Zookie;
import dev.zanzibar.store.InMemoryTupleStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Demonstrates, empirically, why bounded staleness matters for performance.
 *
 * The same workload — a burst of checks carrying scattered, older zookies — is run
 * two ways over an identical store:
 *   A) AtLeastAsFresh with quantization: the policy coalesces every zookie onto one
 *      recent evaluation revision, so the checks share cache keys → high hit rate.
 *   B) AtExactSnapshot: each distinct zookie is its own snapshot → keys fan out →
 *      low hit rate.
 *
 * This is the mechanism behind Zanzibar's high cache-reuse ratio, made measurable.
 */
class CacheHitRateBenchmarkTest {

    private static final int DOCS = 5;
    private static final int USERS = 5;
    private static final int HISTORY = 100;   // revisions of writes/deletes to build up
    private static final int QUERIES = 2000;  // checks in the measured burst

    @Test
    void quantizationLiftsCacheHitRate() {
        var store = new InMemoryTupleStore();
        ObjectRef[] docs = new ObjectRef[DOCS];
        SubjectRef[] users = new SubjectRef[USERS];
        for (int i = 0; i < DOCS; i++) docs[i] = new ObjectRef("doc", "d" + i);
        for (int i = 0; i < USERS; i++) users[i] = SubjectRef.user("user", "u" + i);

        // Build a history so latest/safe = HISTORY.
        Random rng = new Random(42);
        for (int r = 0; r < HISTORY; r++) {
            ObjectRef d = docs[rng.nextInt(DOCS)];
            SubjectRef u = users[rng.nextInt(USERS)];
            if (rng.nextBoolean()) store.write(d, "viewer", u);
            else store.delete(d, "viewer", u);
        }

        // A fixed workload: (doc, user, olderZookie) triples — identical for both runs.
        record Q(ObjectRef doc, SubjectRef user, Zookie bound) {}
        List<Q> workload = new ArrayList<>(QUERIES);
        Random wr = new Random(7);
        for (int i = 0; i < QUERIES; i++) {
            workload.add(new Q(
                    docs[wr.nextInt(DOCS)],
                    users[wr.nextInt(USERS)],
                    new Zookie(1 + wr.nextInt(HISTORY / 2)))); // zookies in [1, 50]
        }

        // A) quantized bounded-staleness
        var quantizedCache = new CheckCache();
        var quantized = new CheckEngine(store, Map.of(), quantizedCache, ConsistencyPolicy.withQuantum(25));
        for (Q q : workload) {
            quantized.check(q.doc(), "viewer", q.user(), Consistency.atLeastAsFresh(q.bound()));
        }

        // B) exact snapshot at the same scattered zookies
        var exactCache = new CheckCache();
        var exact = new CheckEngine(store, Map.of(), exactCache, ConsistencyPolicy.noQuantization());
        for (Q q : workload) {
            exact.check(q.doc(), "viewer", q.user(), Consistency.atExactSnapshot(q.bound()));
        }

        double quantizedRate = quantizedCache.hitRate();
        double exactRate = exactCache.hitRate();

        System.out.printf("Cache hit rate — quantized AtLeastAsFresh: %.1f%% (%d entries) | "
                        + "exact snapshot: %.1f%% (%d entries)%n",
                quantizedRate * 100, quantizedCache.size(),
                exactRate * 100, exactCache.size());

        // Every zookie (<= 50) coalesces onto floor(safe=100, q=25) = 100, so the
        // whole burst shares at most DOCS*USERS keys.
        assertTrue(quantizedCache.size() <= DOCS * USERS,
                "quantization should collapse the burst onto one revision's worth of keys");
        assertTrue(quantizedRate > 0.9,
                "quantized reuse should be very high, was " + quantizedRate);
        assertTrue(quantizedRate > exactRate + 0.2,
                "quantization should clearly beat exact-snapshot reuse: "
                        + quantizedRate + " vs " + exactRate);
    }
}
