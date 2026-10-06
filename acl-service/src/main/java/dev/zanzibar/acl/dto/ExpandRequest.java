package dev.zanzibar.acl.dto;

import dev.zanzibar.model.ObjectRef;
import dev.zanzibar.model.Zookie;

public record ExpandRequest(
        String resourceNs,
        String resourceId,
        String relation,
        Long zookieRevision
) {
    public ObjectRef toObjectRef() {
        return new ObjectRef(resourceNs, resourceId);
    }

    public Zookie toZookie() {
        return zookieRevision == null ? null : new Zookie(zookieRevision);
    }
}
