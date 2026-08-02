package dev.zanzibar.consistency;

import dev.zanzibar.cache.CheckCache;
import dev.zanzibar.engine.CheckEngine;
import dev.zanzibar.model.ObjectRef;
import dev.zanzibar.model.SubjectRef;
import dev.zanzibar.model.Zookie;
import dev.zanzibar.store.InMemoryTupleStore;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The cache must be an optimization, never a semantic change.
 *
 * Invariant (semantics-agnostic): for any sequence of writes/deletes and any
 * zookie, {@code check(..., zookie)} must return the SAME answer whether or not
 * the check cache is enabled. A cache that changes an answer is a consistency bug.
 *
 * The engine implements exact-snapshot reads (see NewEnemyTest.revokeAndReGrant):
 * check(z) reflects state exactly as of revision z, via floorEntry(z). These tests
 * show the revision-aware cache violated that by serving a result computed at a
 * NEWER revision for a query at an OLDER revision.
 */
class CacheConsistencyTest {

    private final ObjectRef doc = new ObjectRef("doc", "readme");
    private final SubjectRef bob = SubjectRef.user("user", "bob");

    /**
     * Warm the cache at the newest revision, then query an older one.
     * Without the cache the older query sees the tombstone; with the buggy
     * cache it saw the newer "true" and leaked a revoked grant.
     */
    @Test
    void cacheDoesNotLeakNewerGrantIntoOlderQuery() {
        var store = new InMemoryTupleStore();
        var cached = new CheckEngine(store, Map.of(), new CheckCache());
        var uncached = new CheckEngine(store, Map.of());

        Zookie z1 = store.write(doc, "viewer", bob);   // rev 1: granted
        Zookie z2 = store.delete(doc, "viewer", bob);  // rev 2: revoked
        Zookie z3 = store.write(doc, "viewer", bob);   // rev 3: re-granted

        // Warm the cache at the newest revision (true @ rev 3).
        assertTrue(cached.check(doc, "viewer", bob, z3));

        // Now the critical query: at z2, Bob is revoked (tombstone at rev 2).
        boolean expected = uncached.check(doc, "viewer", bob, z2); // false
        boolean actual = cached.check(doc, "viewer", bob, z2);

        assertFalse(expected, "sanity: uncached engine denies at z2");
        assertEquals(expected, actual,
                "enabling the cache must not change the answer at z2");
    }

    /**
     * Differential sweep: run an identical grant/revoke history through a
     * cached and an uncached engine, warming the cache at the newest revision
     * before each backward query. They must agree at every revision.
     */
    @Test
    void cacheAndUncachedAgreeAcrossAllRevisions() {
        var store = new InMemoryTupleStore();
        var cached = new CheckEngine(store, Map.of(), new CheckCache());
        var uncached = new CheckEngine(store, Map.of());

        store.write(doc, "viewer", bob);   // rev 1
        store.delete(doc, "viewer", bob);  // rev 2
        store.write(doc, "viewer", bob);   // rev 3
        store.delete(doc, "viewer", bob);  // rev 4
        long latest = store.latestRevision();

        for (long rev = 0; rev <= latest; rev++) {
            // Warm the cache at the newest revision first, to maximise the
            // chance a stale-but-newer entry pollutes the older query.
            cached.check(doc, "viewer", bob, new Zookie(latest));

            Zookie z = new Zookie(rev);
            assertEquals(
                    uncached.check(doc, "viewer", bob, z),
                    cached.check(doc, "viewer", bob, z),
                    "cache changed the answer at revision " + rev);
        }
    }
}
