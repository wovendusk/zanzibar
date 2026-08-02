package dev.zanzibar.consistency;

/**
 * Turns a {@link Consistency} request into a single concrete evaluation timestamp
 * {@code T} — the revision the whole recursive check will read at.
 *
 * <p>This is the piece that makes the result cache both <em>sound</em> and
 * <em>effective</em>. The cache is keyed by the exact evaluation revision, so a
 * cached entry is only ever reused at the identical snapshot it was computed at
 * (soundness). The trick that produces hits is <b>timestamp quantization</b>:
 * for staleness-tolerant modes we round the evaluation revision down to a coarse
 * bucket, so a burst of checks issued close together — with different zookies —
 * collapse onto the <em>same</em> {@code T}, share cache keys, and reuse results.
 * This is the mechanism behind Zanzibar's high cache-hit ratio.
 *
 * <p>Two revision bounds constrain the choice:
 * <ul>
 *   <li>{@code safeRevision} — the highest revision guaranteed durable/replicated.
 *       Staleness-tolerant reads prefer a snapshot at or below this.</li>
 *   <li>{@code latestRevision} — the highest revision that exists. A fresh read
 *       (or a leader read to satisfy a very recent zookie) may go up to here.</li>
 * </ul>
 *
 * <p>Invariants this class guarantees for the returned {@code T}:
 * <ul>
 *   <li>{@code AtLeastAsFresh(z)}: {@code z.revision <= T <= latestRevision}.</li>
 *   <li>{@code AtExactSnapshot(z)}: {@code T == z.revision}.</li>
 *   <li>{@code FullyConsistent}: {@code T == latestRevision}.</li>
 *   <li>{@code MinimizeLatency}: {@code T <= safeRevision} (stale by up to one quantum).</li>
 * </ul>
 */
public final class ConsistencyPolicy {

    private final long quantum;

    public ConsistencyPolicy(long quantum) {
        if (quantum < 1) {
            throw new IllegalArgumentException("quantum must be >= 1, got " + quantum);
        }
        this.quantum = quantum;
    }

    /** A policy with the given bucket size for staleness-tolerant reads. */
    public static ConsistencyPolicy withQuantum(long quantum) {
        return new ConsistencyPolicy(quantum);
    }

    /** quantum = 1: every revision is its own bucket (no coalescing). */
    public static ConsistencyPolicy noQuantization() {
        return new ConsistencyPolicy(1);
    }

    public long quantum() {
        return quantum;
    }

    /**
     * Resolve the evaluation revision for a check.
     *
     * @param consistency    the requested mode
     * @param safeRevision   highest durable/replicated revision ({@code >= 0})
     * @param latestRevision highest revision that exists ({@code >= safeRevision})
     */
    public long resolve(Consistency consistency, long safeRevision, long latestRevision) {
        if (safeRevision < 0 || latestRevision < safeRevision) {
            throw new IllegalArgumentException(
                    "bad bounds: safe=" + safeRevision + " latest=" + latestRevision);
        }
        return switch (consistency) {
            case Consistency.FullyConsistent ignored -> latestRevision;
            case Consistency.MinimizeLatency ignored -> floorToQuantum(safeRevision);
            case Consistency.AtExactSnapshot s -> s.zookie().revision();
            case Consistency.AtLeastAsFresh f ->
                    resolveAtLeastAsFresh(f.zookie().revision(), safeRevision, latestRevision);
        };
    }

    private long resolveAtLeastAsFresh(long lowerBound, long safeRevision, long latestRevision) {
        if (lowerBound > latestRevision) {
            // The requested freshness is beyond anything committed — in a real
            // system we'd block until replication caught up. Single-node: signal it.
            throw new IllegalStateException(
                    "zookie revision " + lowerBound + " is newer than latest revision "
                            + latestRevision + "; cannot satisfy without waiting for replication");
        }
        long sharedBucket = floorToQuantum(safeRevision);
        if (sharedBucket >= lowerBound) {
            // Common case: the zookie is older than the shared safe bucket, so every
            // such request coalesces onto this one timestamp → maximum cache reuse.
            return sharedBucket;
        }
        // The zookie is fresher than the shared bucket. Round it up to the next
        // bucket boundary (so near-simultaneous fresh reads still coalesce), but
        // never below the requested bound and never beyond what actually exists.
        long aligned = ceilToQuantum(lowerBound);
        return Math.max(lowerBound, Math.min(aligned, latestRevision));
    }

    private long floorToQuantum(long v) {
        return (v / quantum) * quantum;
    }

    private long ceilToQuantum(long v) {
        return ((v + quantum - 1) / quantum) * quantum;
    }
}
