package dev.zanzibar.consistency;

import dev.zanzibar.model.Zookie;

import java.util.Objects;

/**
 * The consistency requirement a caller attaches to a Check.
 *
 * A zookie is a <em>lower bound</em> on freshness, not an exact point in time.
 * These four modes span the trade-off between latency/cache-reuse and freshness,
 * mirroring the options a production authorization service (e.g. SpiceDB) exposes.
 *
 * <ul>
 *   <li>{@link MinimizeLatency} — cheapest correct answer; may be stale up to one
 *       quantum. Used when no freshness bound is needed.</li>
 *   <li>{@link AtLeastAsFresh} — bounded staleness. Evaluate at some snapshot
 *       {@code >= zookie}. This is the everyday mode: content stamped with a zookie
 *       is checked "at least as fresh as" that stamp, which is what solves the
 *       new-enemy problem.</li>
 *   <li>{@link AtExactSnapshot} — time-travel. Evaluate at exactly {@code zookie}.
 *       Stronger (and stricter) than the paper's default; useful for reproducible
 *       point-in-time reads and audits.</li>
 *   <li>{@link FullyConsistent} — evaluate at the very latest revision. No staleness,
 *       lowest cache reuse.</li>
 * </ul>
 */
public sealed interface Consistency {

    /** Cheapest correct answer: the freshest quantized <em>safe</em> snapshot. */
    record MinimizeLatency() implements Consistency {}

    /** Bounded staleness: a snapshot at least as fresh as {@code zookie}. */
    record AtLeastAsFresh(Zookie zookie) implements Consistency {
        public AtLeastAsFresh {
            Objects.requireNonNull(zookie, "zookie");
        }
    }

    /** Exact snapshot / time-travel: evaluate at exactly {@code zookie}'s revision. */
    record AtExactSnapshot(Zookie zookie) implements Consistency {
        public AtExactSnapshot {
            Objects.requireNonNull(zookie, "zookie");
        }
    }

    /** Strongest: evaluate at the latest revision that exists. */
    record FullyConsistent() implements Consistency {}

    // --- Factories (read nicely at call sites) ---

    static Consistency minimizeLatency() {
        return new MinimizeLatency();
    }

    static Consistency atLeastAsFresh(Zookie zookie) {
        return new AtLeastAsFresh(zookie);
    }

    static Consistency atExactSnapshot(Zookie zookie) {
        return new AtExactSnapshot(zookie);
    }

    static Consistency fullyConsistent() {
        return new FullyConsistent();
    }
}
