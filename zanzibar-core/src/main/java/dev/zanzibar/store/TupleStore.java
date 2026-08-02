package dev.zanzibar.store;

import dev.zanzibar.model.ObjectRef;
import dev.zanzibar.model.RelationTuple;
import dev.zanzibar.model.SubjectRef;
import dev.zanzibar.model.Zookie;

import java.util.List;

/**
 * Storage layer for relation tuples.
 * All mutating operations return a {@link Zookie} encoding the revision at which
 * the mutation was applied.
 */
public interface TupleStore {

    /** Write a tuple and return the assigned revision as a zookie. */
    Zookie write(ObjectRef resource, String relation, SubjectRef subject);

    /**
     * Soft-delete a tuple by writing a tombstone.
     * Returns the revision of the tombstone.
     */
    Zookie delete(ObjectRef resource, String relation, SubjectRef subject);

    /**
     * Read all active tuples for a given (resource, relation) as of maxRevision.
     * Tombstoned tuples are excluded.
     */
    List<RelationTuple> read(ObjectRef resource, String relation, long maxRevision);

    /**
     * Check whether a specific tuple is active as of maxRevision.
     */
    boolean exists(ObjectRef resource, String relation, SubjectRef subject, long maxRevision);

    /** Return the current (highest) revision number. */
    long latestRevision();

    /**
     * The highest revision guaranteed durable / replicated everywhere — the
     * freshest snapshot a staleness-tolerant read may use without a leader read.
     * Defaults to {@link #latestRevision()} (synchronous single-node replication);
     * a store that models replication lag returns a value that trails it.
     */
    default long safeRevision() {
        return latestRevision();
    }
}
