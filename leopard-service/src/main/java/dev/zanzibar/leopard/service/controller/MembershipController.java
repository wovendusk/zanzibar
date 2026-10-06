package dev.zanzibar.leopard.service.controller;

import dev.zanzibar.leopard.LeopardIndex;
import dev.zanzibar.leopard.service.client.AclClient;
import dev.zanzibar.leopard.service.dto.MembershipRequest;
import dev.zanzibar.leopard.service.dto.MembershipResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/membership")
public class MembershipController {

    private final LeopardIndex index;
    private final AclClient acl;

    public MembershipController(LeopardIndex index, AclClient acl) {
        this.index = index;
        this.acl = acl;
    }

    /**
     * Is the member in the group?
     *
     * The index runs behind the tuple store, because changes reach it through
     * Kafka. If the caller sends a zookie and the index has not yet caught up
     * to that revision, the index might still show a membership that has been
     * removed, so the question is passed to the ACL service instead.
     */
    @PostMapping("/check")
    public MembershipResponse check(@RequestBody MembershipRequest request) {
        long indexedThrough = index.indexedThrough();
        Long required = request.zookieRevision();

        if (required != null && indexedThrough < required) {
            boolean member = acl.isMember(request, "member");
            return new MembershipResponse(member, "ACL_FALLBACK", indexedThrough);
        }
        boolean member = index.isMember(request.toMember(), request.toGroup());
        return new MembershipResponse(member, "LEOPARD_INDEX", indexedThrough);
    }

    /** The revision the index has caught up to. */
    @GetMapping("/freshness")
    public long freshness() {
        return index.indexedThrough();
    }
}
