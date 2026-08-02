package dev.zanzibar.engine;

import dev.zanzibar.consistency.Consistency;
import dev.zanzibar.consistency.ConsistencyPolicy;
import dev.zanzibar.model.Zookie;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the evaluation-timestamp selection algorithm — the pure logic
 * that turns a consistency requirement into a single revision to read at.
 */
class ConsistencyPolicyTest {

    @Test
    void fullyConsistentAlwaysUsesLatest() {
        var p = ConsistencyPolicy.withQuantum(10);
        assertEquals(100, p.resolve(new Consistency.FullyConsistent(), 80, 100));
    }

    @Test
    void exactSnapshotIgnoresQuantumAndBounds() {
        var p = ConsistencyPolicy.withQuantum(10);
        assertEquals(37, p.resolve(new Consistency.AtExactSnapshot(new Zookie(37)), 80, 100),
                "exact snapshot reads at precisely the zookie revision");
    }

    @Test
    void minimizeLatencyRoundsSafeDownToQuantum() {
        var p = ConsistencyPolicy.withQuantum(10);
        // safe = 87 → floor to the 80 bucket. Never fresher than safe.
        assertEquals(80, p.resolve(new Consistency.MinimizeLatency(), 87, 100));
    }

    @Test
    void atLeastAsFresh_oldZookie_coalescesOntoSharedSafeBucket() {
        var p = ConsistencyPolicy.withQuantum(10);
        // Zookie 12 is older than floor(safe=87)=80, so we serve the shared bucket 80.
        // This is the coalescing that drives cache reuse: any zookie <= 80 lands here.
        assertEquals(80, p.resolve(new Consistency.AtLeastAsFresh(new Zookie(12)), 87, 100));
        assertEquals(80, p.resolve(new Consistency.AtLeastAsFresh(new Zookie(80)), 87, 100));
    }

    @Test
    void atLeastAsFresh_freshZookie_roundsUpButNeverBelowBound() {
        var p = ConsistencyPolicy.withQuantum(10);
        // Zookie 83 is fresher than floor(safe=87)=80, so we can't use 80.
        // Round up to the 90 bucket (>= 83, <= latest 100) so nearby fresh reads still share.
        long t = p.resolve(new Consistency.AtLeastAsFresh(new Zookie(83)), 87, 100);
        assertEquals(90, t);
        assertTrue(t >= 83, "must satisfy the freshness lower bound");
    }

    @Test
    void atLeastAsFresh_veryFreshZookie_cappedAtLatest() {
        var p = ConsistencyPolicy.withQuantum(10);
        // Zookie 96: rounding up gives 100 which is exactly latest — fine.
        assertEquals(100, p.resolve(new Consistency.AtLeastAsFresh(new Zookie(96)), 87, 100));
    }

    @Test
    void atLeastAsFresh_roundUpWouldOvershootLatest_fallsBackToLatest() {
        var p = ConsistencyPolicy.withQuantum(10);
        // Zookie 100 == latest; ceil(100)=100, min(100, latest 100)=100, >= bound. Leader read.
        assertEquals(100, p.resolve(new Consistency.AtLeastAsFresh(new Zookie(100)), 87, 100));
    }

    @Test
    void atLeastAsFresh_beyondLatest_throws() {
        var p = ConsistencyPolicy.withQuantum(10);
        assertThrows(IllegalStateException.class,
                () -> p.resolve(new Consistency.AtLeastAsFresh(new Zookie(101)), 87, 100),
                "cannot satisfy a zookie newer than anything committed");
    }

    @Test
    void quantumOfOneMeansNoCoalescing() {
        var p = ConsistencyPolicy.noQuantization();
        assertEquals(87, p.resolve(new Consistency.MinimizeLatency(), 87, 100));
        assertEquals(87, p.resolve(new Consistency.AtLeastAsFresh(new Zookie(12)), 87, 100),
                "with quantum 1, the shared bucket is just safe itself");
        // A bound below safe still resolves UP to safe — bounded staleness may serve
        // a fresher snapshot than requested, which is what enables coalescing.
        assertEquals(87, p.resolve(new Consistency.AtLeastAsFresh(new Zookie(50)), 87, 100));
    }

    @Test
    void rejectsBadBounds() {
        var p = ConsistencyPolicy.withQuantum(10);
        assertThrows(IllegalArgumentException.class,
                () -> p.resolve(new Consistency.MinimizeLatency(), 100, 80),
                "latest < safe is invalid");
        assertThrows(IllegalArgumentException.class, () -> ConsistencyPolicy.withQuantum(0));
    }

    /**
     * The core invariant across a wide grid of inputs: for AtLeastAsFresh the
     * resolved timestamp is always within [zookie, latest] — never stale past the
     * bound, never beyond what exists.
     */
    @Test
    void atLeastAsFresh_invariant_bounded() {
        long[] quanta = {1, 3, 8, 16, 50};
        for (long q : quanta) {
            var p = ConsistencyPolicy.withQuantum(q);
            for (long latest = 0; latest <= 60; latest++) {
                for (long safe = 0; safe <= latest; safe++) {
                    for (long zk = 0; zk <= latest; zk++) {
                        long t = p.resolve(new Consistency.AtLeastAsFresh(new Zookie(zk)), safe, latest);
                        assertTrue(t >= zk && t <= latest,
                                "q=" + q + " safe=" + safe + " latest=" + latest
                                        + " zk=" + zk + " -> t=" + t + " out of [zk, latest]");
                    }
                }
            }
        }
    }
}
