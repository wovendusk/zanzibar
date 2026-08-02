package dev.zanzibar.intelligence.dto;

public record ExplainRequest(
        String resourceNs,
        String resourceId,
        String relation,
        String subjectNs,
        String subjectId,
        String subjectRel
) {}
