package dev.zanzibar.acl.dto;

import dev.zanzibar.model.ObjectRef;

public record ExpandRequest(
        String resourceNs,
        String resourceId,
        String relation,
        Long zookieRevision
) {
    public ObjectRef toObjectRef() {
        return new ObjectRef(resourceNs, resourceId);
    }
}
