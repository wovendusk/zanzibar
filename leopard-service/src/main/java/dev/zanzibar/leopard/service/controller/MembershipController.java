package dev.zanzibar.leopard.service.controller;

import dev.zanzibar.leopard.LeopardIndex;
import dev.zanzibar.leopard.service.dto.MembershipRequest;
import dev.zanzibar.leopard.service.dto.MembershipResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/membership")
public class MembershipController {

    private final LeopardIndex index;

    public MembershipController(LeopardIndex index) {
        this.index = index;
    }

    @PostMapping("/check")
    public ResponseEntity<MembershipResponse> checkMembership(@RequestBody MembershipRequest req) {
        boolean member = index.isMember(req.toMember(), req.toGroup());
        return ResponseEntity.ok(new MembershipResponse(member, index.indexedThrough()));
    }

    @GetMapping("/freshness")
    public ResponseEntity<Long> freshness() {
        return ResponseEntity.ok(index.indexedThrough());
    }
}
