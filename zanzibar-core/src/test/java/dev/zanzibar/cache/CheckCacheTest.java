package dev.zanzibar.cache;

import dev.zanzibar.config.NamespaceConfig;
import dev.zanzibar.config.RewriteRule;
import dev.zanzibar.engine.CheckEngine;
import dev.zanzibar.model.ObjectRef;
import dev.zanzibar.model.SubjectRef;
import dev.zanzibar.model.Zookie;
import dev.zanzibar.store.InMemoryTupleStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class CheckCacheTest {

    private CheckCache cache;

    private final ObjectRef doc = new ObjectRef("doc", "readme");
    private final SubjectRef alice = SubjectRef.user("user", "alice");
    private final SubjectRef bob = SubjectRef.user("user", "bob");

    @BeforeEach
    void setUp() {
        cache = new CheckCache();
    }

    @Test
    void lookupMissOnEmptyCache() {
        assertEquals(Optional.empty(), cache.lookup(doc, "viewer", alice, 1));
    }

    @Test
    void lookupHitAtSameRevision() {
        cache.store(doc, "viewer", alice, true, 5);
        assertEquals(Optional.of(true), cache.lookup(doc, "viewer", alice, 5));
    }

    @Test
    void lookupMissAtOlderRevision() {
        cache.store(doc, "viewer", alice, true, 5);
        // Exact-snapshot key: an entry at rev 5 says nothing about rev 3.
        assertEquals(Optional.empty(), cache.lookup(doc, "viewer", alice, 3),
                "Cache entry at rev 5 must not answer a query at rev 3");
    }

    @Test
    void lookupMissAtNewerRevision() {
        cache.store(doc, "viewer", alice, true, 5);
        assertEquals(Optional.empty(), cache.lookup(doc, "viewer", alice, 7),
                "Cache entry at rev 5 must not answer a query at rev 7");
    }

    @Test
    void cacheStoresBothTrueAndFalse() {
        cache.store(doc, "viewer", alice, true, 5);
        cache.store(doc, "viewer", bob, false, 5);

        assertEquals(Optional.of(true), cache.lookup(doc, "viewer", alice, 5));
        assertEquals(Optional.of(false), cache.lookup(doc, "viewer", bob, 5));
    }

    @Test
    void distinctRevisionsAreIndependentEntries() {
        // Same (resource, relation, subject) evaluated at two revisions — the state
        // legitimately differs, so both snapshots are kept side by side.
        cache.store(doc, "viewer", alice, true, 5);
        cache.store(doc, "viewer", alice, false, 8);

        assertEquals(Optional.of(true), cache.lookup(doc, "viewer", alice, 5));
        assertEquals(Optional.of(false), cache.lookup(doc, "viewer", alice, 8));
        assertEquals(Optional.empty(), cache.lookup(doc, "viewer", alice, 6),
                "no entry was computed at rev 6");
    }

    @Test
    void evictBeforeDropsOldSnapshots() {
        cache.store(doc, "viewer", alice, true, 3);
        cache.store(doc, "viewer", alice, false, 8);

        cache.evictBefore(5);

        assertEquals(Optional.empty(), cache.lookup(doc, "viewer", alice, 3),
                "rev 3 entry evicted");
        assertEquals(Optional.of(false), cache.lookup(doc, "viewer", alice, 8),
                "rev 8 entry retained");
    }

    @Test
    void metricsCountHitsAndMisses() {
        cache.store(doc, "viewer", alice, true, 5);

        cache.lookup(doc, "viewer", alice, 5); // hit
        cache.lookup(doc, "viewer", alice, 5); // hit
        cache.lookup(doc, "viewer", alice, 6); // miss
        cache.lookup(doc, "viewer", bob, 5);   // miss

        assertEquals(2, cache.hitCount());
        assertEquals(2, cache.missCount());
        assertEquals(0.5, cache.hitRate(), 1e-9);
    }

    @Test
    void cacheIntegrationWithEngine() {
        var store = new InMemoryTupleStore();
        var integrationCache = new CheckCache();
        var docConfig = NamespaceConfig.builder("doc")
                .relation("viewer", RewriteRule.thisRelation())
                .build();
        var engine = new CheckEngine(store, Map.of("doc", docConfig), integrationCache);

        ObjectRef d = new ObjectRef("doc", "test");
        SubjectRef user = SubjectRef.user("user", "tester");
        Zookie z = store.write(d, "viewer", user);

        assertTrue(engine.check(d, "viewer", user, z));
        assertTrue(integrationCache.size() > 0, "Cache should have entries after check");

        assertTrue(engine.check(d, "viewer", user, z),
                "Second check at the same revision should hit cache and return the same result");
    }
}
