package dev.zanzibar.cache;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Cache of check results, keyed by the question and the revision it was
 * evaluated at.
 *
 * The store is append-only, so the data visible at a given revision never
 * changes. An answer computed at revision R is therefore correct for R forever
 * and there is nothing to invalidate; a revoke creates a new revision, and
 * checks at that revision use different keys. Old revisions are evicted once
 * no check can be evaluated at them any more.
 *
 * Many request threads use the cache at once, so every method is synchronized.
 */
public class CheckCache {

    private final Map<CheckCacheKey, Boolean> entries = new HashMap<>();
    private long evictedBefore = 0;
    private long hits = 0;
    private long misses = 0;

    /** The cached answer, or null if there is none. */
    public synchronized Boolean lookup(CheckCacheKey key) {
        Boolean result = entries.get(key);
        if (result == null) {
            misses++;
        } else {
            hits++;
        }
        return result;
    }

    public synchronized void store(CheckCacheKey key, boolean result) {
        entries.put(key, result);
    }

    /** Drop every entry computed at a revision older than cutoffRevision. */
    public synchronized void evictBefore(long cutoffRevision) {
        if (cutoffRevision <= evictedBefore) {
            return; // already done
        }
        Iterator<CheckCacheKey> keys = entries.keySet().iterator();
        while (keys.hasNext()) {
            if (keys.next().revision() < cutoffRevision) {
                keys.remove();
            }
        }
        evictedBefore = cutoffRevision;
    }

    public synchronized void clear() {
        entries.clear();
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized long hitCount() {
        return hits;
    }

    public synchronized long missCount() {
        return misses;
    }
}
