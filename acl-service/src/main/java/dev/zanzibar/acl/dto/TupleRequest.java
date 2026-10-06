package dev.zanzibar.acl.dto;

import dev.zanzibar.model.ObjectRef;
import dev.zanzibar.model.SubjectRef;

/** JSON body for writing or deleting a tuple. subjectRel is omitted for a plain user. */
public record TupleRequest(
        String resourceNs,
        String resourceId,
        String relation,
        String subjectNs,
        String subjectId,
        String subjectRel
) {
    public ObjectRef toObjectRef() {
        return new ObjectRef(resourceNs, resourceId);
    }

    public SubjectRef toSubjectRef() {
        return new SubjectRef(subjectNs, subjectId, subjectRel);
    }
}
