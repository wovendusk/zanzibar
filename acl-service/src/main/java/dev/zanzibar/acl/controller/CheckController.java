package dev.zanzibar.acl.controller;

import dev.zanzibar.ZanzibarEngine;
import dev.zanzibar.acl.dto.CheckRequest;
import dev.zanzibar.acl.dto.CheckResponse;
import dev.zanzibar.acl.dto.CheckWithTraceResponse;
import dev.zanzibar.acl.dto.ExpandRequest;
import dev.zanzibar.engine.UsersetTree;
import dev.zanzibar.model.Zookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class CheckController {

    private final ZanzibarEngine engine;

    public CheckController(ZanzibarEngine engine) {
        this.engine = engine;
    }

    @PostMapping("/check")
    public ResponseEntity<CheckResponse> check(@RequestBody CheckRequest req) {
        boolean granted;
        long evalRev;
        if (req.zookieRevision() != null) {
            Zookie z = new Zookie(req.zookieRevision());
            granted = engine.check(req.toObjectRef(), req.relation(), req.toSubjectRef(), z);
            evalRev = z.revision();
        } else {
            granted = engine.check(req.toObjectRef(), req.relation(), req.toSubjectRef());
            evalRev = engine.getStore().latestRevision();
        }
        return ResponseEntity.ok(new CheckResponse(granted, evalRev));
    }

    @PostMapping("/check/explain")
    public ResponseEntity<CheckWithTraceResponse> checkWithTrace(@RequestBody CheckRequest req) {
        Zookie z = req.zookieRevision() != null
                ? new Zookie(req.zookieRevision())
                : engine.currentZookie();
        String query = "Can " + req.subjectNs() + ":" + req.subjectId()
                + " " + req.relation() + " " + req.resourceNs() + ":" + req.resourceId() + "?";
        var result = engine.checkAndExplain(req.toObjectRef(), req.relation(), req.toSubjectRef(), z, query);
        return ResponseEntity.ok(new CheckWithTraceResponse(
                result.granted(),
                result.trace().toStructuredText(),
                result.prompt().systemPrompt(),
                result.prompt().userMessage()));
    }

    @PostMapping("/expand")
    public ResponseEntity<UsersetTree> expand(@RequestBody ExpandRequest req) {
        UsersetTree tree;
        if (req.zookieRevision() != null) {
            tree = engine.expand(req.toObjectRef(), req.relation(), new Zookie(req.zookieRevision()));
        } else {
            tree = engine.expand(req.toObjectRef(), req.relation());
        }
        return ResponseEntity.ok(tree);
    }
}
