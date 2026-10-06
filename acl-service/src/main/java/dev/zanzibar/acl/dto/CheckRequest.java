package dev.zanzibar.acl.dto;

import dev.zanzibar.model.ObjectRef;
import dev.zanzibar.model.SubjectRef;
import dev.zanzibar.model.Zookie;

/**
 * JSON body for a check. zookieRevision is optional: when present the check
 * is evaluated at a snapshot at least that fresh.
 */
public record CheckRequest(
        String resourceNs,
        String resourceId,
        String relation,
        String subjectNs,
        String subjectId,
        String subjectRel,
        Long zookieRevision
) {
    public ObjectRef toObjectRef() {
        return new ObjectRef(resourceNs, resourceId);
    }

    public SubjectRef toSubjectRef() {
        return new SubjectRef(subjectNs, subjectId, subjectRel);
    }

    /** The zookie, or null if the caller did not send one. */
    public Zookie toZookie() {
        return zookieRevision == null ? null : new Zookie(zookieRevision);
    }
}
