package dev.zanzibar.intelligence.dto;

public record AuditEntry(
        String type,
        String resourceNs, String resourceId,
        String relation,
        String subjectNs, String subjectId, String subjectRel,
        long revision,
        String timestamp
) {}
