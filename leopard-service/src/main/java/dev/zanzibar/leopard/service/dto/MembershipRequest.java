package dev.zanzibar.leopard.service.dto;

import dev.zanzibar.model.ObjectRef;
import dev.zanzibar.model.SubjectRef;

public record MembershipRequest(
        String memberNs,
        String memberId,
        String groupNs,
        String groupId
) {
    public SubjectRef toMember() {
        return SubjectRef.user(memberNs, memberId);
    }

    public ObjectRef toGroup() {
        return new ObjectRef(groupNs, groupId);
    }
}
