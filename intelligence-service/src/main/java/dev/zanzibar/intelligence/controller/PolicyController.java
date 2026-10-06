package dev.zanzibar.intelligence.controller;

import dev.zanzibar.intelligence.dto.PolicyRequest;
import dev.zanzibar.intelligence.dto.PolicyResponse;
import dev.zanzibar.intelligence.service.PolicyService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/policy")
public class PolicyController {

    private final PolicyService policyService;

    public PolicyController(PolicyService policyService) {
        this.policyService = policyService;
    }

    @PostMapping("/compile")
    public PolicyResponse compile(@RequestBody PolicyRequest request) {
        boolean install = request.install() != null && request.install();
        return policyService.compile(request.description(), install);
    }
}
