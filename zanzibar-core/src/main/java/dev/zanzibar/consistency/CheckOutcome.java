package dev.zanzibar.consistency;

/**
 * The result of a consistency-aware check.
 *
 * @param granted             whether the subject has the relation
 * @param evaluatedAtRevision the exact snapshot the engine actually evaluated at;
 *                            for {@code AtLeastAsFresh(z)} this is {@code >= z} and
 *                            may be fresher than requested (bounded staleness).
 */
public record CheckOutcome(boolean granted, long evaluatedAtRevision) {}
