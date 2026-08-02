package dev.zanzibar.acl.dto;

import dev.zanzibar.model.ObjectRef;
import dev.zanzibar.model.SubjectRef;

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
        return subjectRel != null
                ? SubjectRef.userset(subjectNs, subjectId, subjectRel)
                : SubjectRef.user(subjectNs, subjectId);
    }
}
