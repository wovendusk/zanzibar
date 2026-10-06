package dev.zanzibar.intelligence.dto;

/** The check to explain. Sent on unchanged to the ACL service. */
public record ExplainRequest(
        String resourceNs,
        String resourceId,
        String relation,
        String subjectNs,
        String subjectId,
        String subjectRel,
        Long zookieRevision
) {
}
