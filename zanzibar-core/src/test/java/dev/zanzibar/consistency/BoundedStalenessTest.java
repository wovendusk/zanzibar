package dev.zanzibar.consistency;

import dev.zanzibar.engine.CheckEngine;
import dev.zanzibar.model.ObjectRef;
import dev.zanzibar.model.SubjectRef;
import dev.zanzibar.model.Zookie;
import dev.zanzibar.store.InMemoryTupleStore;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end guarantees of the bounded-staleness consistency model.
 *
 * The central promise: a check evaluated "at least as fresh as" a zookie reflects
 * every ACL change committed up to that zookie — possibly more. That single
 * property is what keeps the new-enemy attack closed while still letting the engine
 * serve a fresher, cache-friendly snapshot.
 */
class BoundedStalenessTest {

    private final ObjectRef doc = new ObjectRef("doc", "secret");
    private final SubjectRef bob = SubjectRef.user("user", "bob");

    private CheckEngine engine(InMemoryTupleStore store, long quantum) {
        return new CheckEngine(store, Map.of(), null, ConsistencyPolicy.withQuantum(quantum));
    }

    @Test
    void newEnemyStillSolvedUnderBoundedStaleness() {
        var store = new InMemoryTupleStore();
        var engine = engine(store, 8);

        store.write(doc, "viewer", bob);            // rev 1: granted
        store.delete(doc, "viewer", bob);           // rev 2: revoked
        Zookie contentZookie = new Zookie(store.latestRevision()); // content stamped at rev 2

        CheckOutcome outcome = engine.check(doc, "viewer", bob,
                Consistency.atLeastAsFresh(contentZookie));

        assertFalse(outcome.granted(),
                "a check at least as fresh as the content must see the revocation");
        assertTrue(outcome.evaluatedAtRevision() >= contentZookie.revision(),
                "bounded staleness guarantees the evaluation snapshot is >= the zookie");
    }

    @Test
    void readYourOwnWrites() {
        var store = new InMemoryTupleStore();
        var engine = engine(store, 8);

        Zookie afterWrite = store.write(doc, "viewer", bob);
        CheckOutcome outcome = engine.check(doc, "viewer", bob,
                Consistency.atLeastAsFresh(afterWrite));

        assertTrue(outcome.granted(), "you must always see a write you were just handed a zookie for");
    }

    @Test
    void fullyConsistentAlwaysTracksLatest() {
        var store = new InMemoryTupleStore();
        var engine = engine(store, 8);

        store.write(doc, "viewer", bob);
        assertTrue(engine.check(doc, "viewer", bob, Consistency.fullyConsistent()).granted());

        store.delete(doc, "viewer", bob);
        assertFalse(engine.check(doc, "viewer", bob, Consistency.fullyConsistent()).granted());
    }

    /**
     * The safe-vs-latest gap, made observable. With replication paused, a fresh
     * delete has committed (latest advanced) but is not yet "safe". The three modes
     * then diverge exactly as their contracts promise.
     */
    @Test
    void modesDivergeAcrossTheSafeToLatestGap() {
        var store = new InMemoryTupleStore();
        var engine = engine(store, 1); // no quantization: isolate the safe/latest effect

        store.write(doc, "viewer", bob);   // rev 1, safe = 1
        store.pauseReplication();
        Zookie afterDelete = store.delete(doc, "viewer", bob); // rev 2 committed, safe stays 1

        // MinimizeLatency reads the safe snapshot (rev 1) — it has NOT seen the delete yet.
        assertTrue(engine.check(doc, "viewer", bob, Consistency.minimizeLatency()).granted(),
                "minimize-latency serves the safe (lagging) snapshot");

        // FullyConsistent reads latest (rev 2) — sees the delete.
        assertFalse(engine.check(doc, "viewer", bob, Consistency.fullyConsistent()).granted(),
                "fully-consistent always sees the latest committed state");

        // AtLeastAsFresh(delete) must honor its bound via a leader read to rev 2.
        assertFalse(engine.check(doc, "viewer", bob, Consistency.atLeastAsFresh(afterDelete)).granted(),
                "at-least-as-fresh honors the zookie even when safe lags");

        // Once replication catches up, the safe snapshot advances and minimize-latency agrees.
        store.resumeReplication();
        assertFalse(engine.check(doc, "viewer", bob, Consistency.minimizeLatency()).granted(),
                "after catch-up, the safe snapshot reflects the delete");
    }

    @Test
    void outcomeAlwaysMatchesItsReportedSnapshot() {
        var store = new InMemoryTupleStore();
        var engine = engine(store, 4);

        Zookie z1 = store.write(doc, "viewer", bob);
        Zookie z2 = store.delete(doc, "viewer", bob);
        Zookie z3 = store.write(doc, "viewer", bob);

        for (Zookie z : new Zookie[]{z1, z2, z3}) {
            CheckOutcome outcome = engine.check(doc, "viewer", bob, Consistency.atLeastAsFresh(z));
            long t = outcome.evaluatedAtRevision();

            assertTrue(t >= z.revision() && t <= store.latestRevision(),
                    "evaluation snapshot must lie in [zookie, latest]");
            // The engine's answer must equal the ground truth at the snapshot it claims.
            assertEquals(store.exists(doc, "viewer", bob, t), outcome.granted(),
                    "result must match the store at the reported evaluation revision");
        }
    }
}
