package dev.zanzibar.intelligence.dto;

/**
 * @param granted             the engine's decision
 * @param evaluatedAtRevision the snapshot the decision is true for
 * @param trace               the steps the engine took
 * @param explanation         the model's English wording of those steps
 */
public record ExplainResponse(boolean granted, long evaluatedAtRevision, String trace, String explanation) {
}
