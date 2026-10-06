package dev.zanzibar.intelligence.controller;

import dev.zanzibar.intelligence.dto.ExplainRequest;
import dev.zanzibar.intelligence.dto.ExplainResponse;
import dev.zanzibar.intelligence.service.ExplainService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/explain")
public class ExplainController {

    private final ExplainService explainService;

    public ExplainController(ExplainService explainService) {
        this.explainService = explainService;
    }

    @PostMapping
    public ExplainResponse explain(@RequestBody ExplainRequest request) {
        return explainService.explain(request);
    }
}
