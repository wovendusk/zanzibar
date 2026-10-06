package dev.zanzibar.engine;

/**
 * The answer to a check.
 *
 * @param granted             whether the subject has the relation
 * @param evaluatedAtRevision the snapshot the answer is true for
 * @param trace               the decision trace as text, or null if not requested
 */
public record CheckResult(boolean granted, long evaluatedAtRevision, String trace) {
}
