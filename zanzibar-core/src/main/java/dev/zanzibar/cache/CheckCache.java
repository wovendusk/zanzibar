package dev.zanzibar.cache;

import dev.zanzibar.model.ObjectRef;
import dev.zanzibar.model.SubjectRef;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Snapshot-exact check result cache.
 *
 * <p>An entry is keyed by (resource, relation, subject, <b>revision</b>) and is
 * only returned for a lookup at that exact revision. Because tuple history is
 * append-only, the state at a fixed revision is immutable forever, so a cached
 * result at revision R is a permanently-valid answer for any check evaluating at
 * R — and never a valid answer at any other revision. That exactness is what
 * keeps the cache from leaking a grant across a revocation.
 *
 * <p>The cache is only <em>useful</em> because {@code ConsistencyPolicy} coalesces
 * staleness-tolerant checks onto shared evaluation revisions (quantization): many
 * distinct requests resolve to the same R, hit the same key, and reuse the result.
 * Hit/miss counters here let the hit-rate benefit be measured, not just asserted.
 */
public class CheckCache {

    private final ConcurrentHashMap<CheckCacheKey, Boolean> cache = new ConcurrentHashMap<>();
    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();

    /** Look up a result computed at exactly {@code atRevision}. */
    public Optional<Boolean> lookup(ObjectRef resource, String relation,
                                    SubjectRef subject, long atRevision) {
        Boolean result = cache.get(CheckCacheKey.of(resource, relation, subject, atRevision));
        if (result != null) {
            hits.increment();
            return Optional.of(result);
        }
        misses.increment();
        return Optional.empty();
    }

    /** Store a result computed at {@code revision}. */
    public void store(ObjectRef resource, String relation,
                      SubjectRef subject, boolean result, long revision) {
        cache.put(CheckCacheKey.of(resource, relation, subject, revision), result);
    }

    /**
     * Drop entries computed at a revision strictly older than {@code cutoffRevision}.
     * Called with a quantum boundary to bound cache size — old snapshots are no
     * longer worth reusing once every live request coalesces onto newer buckets.
     */
    public void evictBefore(long cutoffRevision) {
        cache.keySet().removeIf(key -> key.revision() < cutoffRevision);
    }

    // --- Metrics ---

    public long hitCount() {
        return hits.sum();
    }

    public long missCount() {
        return misses.sum();
    }

    /** Fraction of lookups that hit, in [0, 1]; 0 when there have been no lookups. */
    public double hitRate() {
        long h = hits.sum();
        long total = h + misses.sum();
        return total == 0 ? 0.0 : (double) h / total;
    }

    public void resetMetrics() {
        hits.reset();
        misses.reset();
    }

    public int size() {
        return cache.size();
    }

    public void clear() {
        cache.clear();
        resetMetrics();
    }
}
