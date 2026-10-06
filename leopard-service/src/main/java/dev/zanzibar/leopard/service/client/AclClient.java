package dev.zanzibar.leopard.service.client;

import dev.zanzibar.leopard.service.dto.MembershipRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Calls the ACL service, which owns the tuple store and is always correct. */
@Component
public class AclClient {

    /** The JSON body the ACL service expects for a check. */
    private record CheckRequest(String resourceNs, String resourceId, String relation,
                                String subjectNs, String subjectId, String subjectRel,
                                Long zookieRevision) {
    }

    /** The JSON the ACL service answers with. */
    private record CheckResponse(boolean granted, long evaluatedAtRevision) {
    }

    private final RestClient http;

    public AclClient(@Value("${acl.service.url}") String aclServiceUrl) {
        this.http = RestClient.builder().baseUrl(aclServiceUrl).build();
    }

    /** Asks the ACL service whether the member is in the group, at a snapshot at least as fresh as the zookie. */
    public boolean isMember(MembershipRequest request, String membershipRelation) {
        CheckRequest body = new CheckRequest(
                request.groupNs(), request.groupId(), membershipRelation,
                request.memberNs(), request.memberId(), null,
                request.zookieRevision());
        CheckResponse response = http.post()
                .uri("/api/v1/check")
                .body(body)
                .retrieve()
                .body(CheckResponse.class);
        return response != null && response.granted();
    }
}
