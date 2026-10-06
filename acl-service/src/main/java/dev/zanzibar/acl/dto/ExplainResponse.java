package dev.zanzibar.acl.dto;

/** A check answer together with the steps the engine took to reach it. */
public record ExplainResponse(boolean granted, long evaluatedAtRevision, String trace) {
}
