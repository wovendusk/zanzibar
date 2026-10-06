package dev.zanzibar.cache;

import dev.zanzibar.model.ObjectRef;
import dev.zanzibar.model.SubjectRef;

/**
 * Identifies one check question at one revision:
 * "does subject have relation on resource, as of revision?"
 *
 * The revision is part of the key, so an answer is only ever reused for the
 * exact snapshot it was computed at.
 */
public record CheckCacheKey(ObjectRef resource, String relation, SubjectRef subject, long revision) {
}
