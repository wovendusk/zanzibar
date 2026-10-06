package dev.zanzibar.leopard.service.dto;

import dev.zanzibar.model.ObjectRef;
import dev.zanzibar.model.SubjectRef;

/** zookieRevision is optional: "the answer must reflect at least this revision". */
public record MembershipRequest(
        String memberNs,
        String memberId,
        String groupNs,
        String groupId,
        Long zookieRevision
) {
    public SubjectRef toMember() {
        return SubjectRef.user(memberNs, memberId);
    }

    public ObjectRef toGroup() {
        return new ObjectRef(groupNs, groupId);
    }
}
