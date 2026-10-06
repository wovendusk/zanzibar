package dev.zanzibar;

import dev.zanzibar.cache.CheckCache;
import dev.zanzibar.cache.CheckCacheKey;
import dev.zanzibar.model.ObjectRef;
import dev.zanzibar.model.SubjectRef;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CheckCacheTest {

    private CheckCacheKey key(long revision) {
        return new CheckCacheKey(new ObjectRef("doc", "readme"), "viewer",
                SubjectRef.user("user", "alice"), revision);
    }

    @Test
    void anAnswerIsOnlyFoundAtTheRevisionItWasStoredAt() {
        CheckCache cache = new CheckCache();
        cache.store(key(5), true);
        assertEquals(true, cache.lookup(key(5)));
        assertNull(cache.lookup(key(6)));
        assertEquals(1, cache.hitCount());
        assertEquals(1, cache.missCount());
    }

    @Test
    void evictBeforeDropsOnlyOlderRevisions() {
        CheckCache cache = new CheckCache();
        cache.store(key(5), true);
        cache.store(key(10), false);
        cache.evictBefore(10);
        assertEquals(1, cache.size());
        assertNull(cache.lookup(key(5)));
        assertEquals(false, cache.lookup(key(10)));
    }
}
