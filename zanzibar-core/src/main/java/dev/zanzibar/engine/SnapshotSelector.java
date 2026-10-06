package dev.zanzibar.engine;

import dev.zanzibar.model.Zookie;

/**
 * Chooses the revision a check is evaluated at.
 *
 * Without a zookie the caller has no freshness requirement, so the revision is
 * rounded down to a multiple of the quantum. Checks that arrive close together
 * then share one revision, and therefore share cache entries. The price is
 * that such a check may not see the last (quantum - 1) writes.
 *
 * With a zookie the caller requires a snapshot at least as fresh as that
 * revision. The rounded revision is used if it is fresh enough, otherwise the
 * zookie's own revision is used. This is what prevents the "new enemy"
 * problem: content saved at revision R is always checked at R or later, so a
 * permission removed before R can never be used to read it.
 */
public class SnapshotSelector {

    private final long quantum;

    public SnapshotSelector(long quantum) {
        if (quantum < 1) {
            throw new IllegalArgumentException("quantum must be at least 1, got " + quantum);
        }
        this.quantum = quantum;
    }

    /** The newest multiple of the quantum that is not after latestRevision. */
    public long roundDown(long latestRevision) {
        return (latestRevision / quantum) * quantum;
    }

    /**
     * @param atLeastAsFresh the caller's zookie, or null for no requirement
     * @param latestRevision the newest revision in the store
     */
    public long select(Zookie atLeastAsFresh, long latestRevision) {
        long rounded = roundDown(latestRevision);
        if (atLeastAsFresh == null) {
            return rounded;
        }
        long required = atLeastAsFresh.revision();
        if (required > latestRevision) {
            throw new IllegalArgumentException("zookie revision " + required
                    + " is newer than the latest revision " + latestRevision);
        }
        return Math.max(required, rounded);
    }
}
