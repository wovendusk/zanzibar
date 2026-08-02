package dev.zanzibar.intelligence.controller;

import dev.zanzibar.intelligence.dto.ExplainRequest;
import dev.zanzibar.intelligence.dto.ExplainResponse;
import dev.zanzibar.intelligence.service.ExplainService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/explain")
public class ExplainController {

    private final ExplainService explainService;

    public ExplainController(ExplainService explainService) {
        this.explainService = explainService;
    }

    @PostMapping
    public ResponseEntity<ExplainResponse> explain(@RequestBody ExplainRequest req) {
        return ResponseEntity.ok(explainService.explain(
                req.resourceNs(), req.resourceId(), req.relation(),
                req.subjectNs(), req.subjectId(), req.subjectRel()));
    }
}
