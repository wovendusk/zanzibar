package dev.zanzibar.intelligence.controller;

import dev.zanzibar.intelligence.dto.AuditEntry;
import dev.zanzibar.intelligence.service.AuditService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping("/resource")
    public ResponseEntity<List<AuditEntry>> byResource(
            @RequestParam String resourceNs,
            @RequestParam String resourceId,
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(auditService.findByResource(resourceNs, resourceId, limit));
    }

    @GetMapping("/subject")
    public ResponseEntity<List<AuditEntry>> bySubject(
            @RequestParam String subjectNs,
            @RequestParam String subjectId,
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(auditService.findBySubject(subjectNs, subjectId, limit));
    }
}
