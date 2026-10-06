package dev.zanzibar.acl.controller;

import dev.zanzibar.ZanzibarEngine;
import dev.zanzibar.acl.dto.CheckRequest;
import dev.zanzibar.acl.dto.CheckResponse;
import dev.zanzibar.acl.dto.ExpandRequest;
import dev.zanzibar.acl.dto.ExplainResponse;
import dev.zanzibar.engine.CheckResult;
import dev.zanzibar.engine.UsersetTree;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Check, check-with-trace and expand. */
@RestController
@RequestMapping("/api/v1")
public class CheckController {

    private final ZanzibarEngine engine;

    public CheckController(ZanzibarEngine engine) {
        this.engine = engine;
    }

    @PostMapping("/check")
    public CheckResponse check(@RequestBody CheckRequest request) {
        CheckResult result = engine.check(request.toObjectRef(), request.relation(),
                request.toSubjectRef(), request.toZookie());
        return new CheckResponse(result.granted(), result.evaluatedAtRevision());
    }

    @PostMapping("/check/explain")
    public ExplainResponse explain(@RequestBody CheckRequest request) {
        CheckResult result = engine.checkWithTrace(request.toObjectRef(), request.relation(),
                request.toSubjectRef(), request.toZookie());
        return new ExplainResponse(result.granted(), result.evaluatedAtRevision(), result.trace());
    }

    @PostMapping("/expand")
    public UsersetTree expand(@RequestBody ExpandRequest request) {
        return engine.expand(request.toObjectRef(), request.relation(), request.toZookie());
    }
}
