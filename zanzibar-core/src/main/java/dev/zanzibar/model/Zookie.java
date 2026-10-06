package dev.zanzibar.model;

/**
 * A consistency token: the revision at which a write was applied.
 *
 * A client stores the zookie it got back from a write next to the content it
 * protects, and sends it with later checks to say "evaluate at a snapshot at
 * least this fresh". In Google's Zanzibar it encodes a Spanner timestamp; here
 * it wraps a revision number handed out by the tuple store.
 */
public record Zookie(long revision) {
}
