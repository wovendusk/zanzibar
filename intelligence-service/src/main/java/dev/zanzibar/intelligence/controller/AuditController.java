package dev.zanzibar.intelligence.controller;

import dev.zanzibar.intelligence.dto.AuditEntry;
import dev.zanzibar.intelligence.service.AuditService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping("/resource")
    public List<AuditEntry> byResource(@RequestParam String resourceNs,
                                       @RequestParam String resourceId,
                                       @RequestParam(defaultValue = "50") int limit) {
        return auditService.findByResource(resourceNs, resourceId, limit);
    }

    @GetMapping("/subject")
    public List<AuditEntry> bySubject(@RequestParam String subjectNs,
                                      @RequestParam String subjectId,
                                      @RequestParam(defaultValue = "50") int limit) {
        return auditService.findBySubject(subjectNs, subjectId, limit);
    }
}
