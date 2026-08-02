package dev.zanzibar.cache;

import dev.zanzibar.model.ObjectRef;
import dev.zanzibar.model.SubjectRef;

/**
 * Cache key for a check result: a (resource, relation, subject) triple pinned to
 * the exact revision it was evaluated at.
 *
 * <p>Including the evaluation revision is what makes the cache sound: an entry is
 * only ever reused at the identical snapshot that produced it, so a result can
 * never leak across a revision boundary where the underlying tuples changed.
 * Cache <em>reuse</em> then comes from {@code ConsistencyPolicy} coalescing many
 * requests onto the same revision, not from relaxing this key.
 */
public record CheckCacheKey(
        String objectNamespace,
        String objectId,
        String relation,
        String subjectNamespace,
        String subjectId,
        String subjectRelation,
        long revision
) {
    public static CheckCacheKey of(ObjectRef resource, String relation, SubjectRef subject, long revision) {
        return new CheckCacheKey(
                resource.namespace(), resource.id(),
                relation,
                subject.namespace(), subject.id(), subject.relation(),
                revision
        );
    }
}
