package dev.zanzibar.store;

import dev.zanzibar.model.ObjectRef;
import dev.zanzibar.model.RelationTuple;
import dev.zanzibar.model.SubjectRef;
import dev.zanzibar.model.Zookie;

import java.util.List;

/**
 * Append-only storage of relation tuples.
 *
 * Nothing is ever updated or removed. A write appends the tuple at a new
 * revision; a delete appends a tombstone at a new revision. Reads name a
 * revision and see the store exactly as it was at that point.
 */
public interface TupleStore {

    /** Append a tuple. Returns the revision it was written at. */
    Zookie write(ObjectRef resource, String relation, SubjectRef subject);

    /** Append a tombstone for a tuple. Returns the revision of the tombstone. */
    Zookie delete(ObjectRef resource, String relation, SubjectRef subject);

    /** All tuples for (resource, relation) that are live as of maxRevision. */
    List<RelationTuple> read(ObjectRef resource, String relation, long maxRevision);

    /** Whether one specific tuple is live as of maxRevision. */
    boolean exists(ObjectRef resource, String relation, SubjectRef subject, long maxRevision);

    /** The highest revision written so far (0 if the store is empty). */
    long latestRevision();
}
