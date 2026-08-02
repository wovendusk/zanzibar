package dev.zanzibar.property;

import dev.zanzibar.cache.CheckCache;
import dev.zanzibar.consistency.CheckOutcome;
import dev.zanzibar.consistency.Consistency;
import dev.zanzibar.consistency.ConsistencyPolicy;
import dev.zanzibar.engine.CheckEngine;
import dev.zanzibar.model.ObjectRef;
import dev.zanzibar.model.SubjectRef;
import dev.zanzibar.model.Zookie;
import dev.zanzibar.store.InMemoryTupleStore;
import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;

import java.util.*;

/**
 * Property-based test for the bounded-staleness contract.
 *
 * For random operation sequences, random quantum, and random "at least as fresh"
 * zookies, the engine (with the cache enabled) must satisfy two things for every
 * check outcome:
 *   1. bound:   zookie.revision <= evaluatedAt <= latestRevision
 *   2. honesty: granted == the ground-truth state at exactly evaluatedAt
 *
 * Property 2 is the strong one: the engine doesn't just return "a plausible
 * answer", it returns the answer that genuinely holds at the snapshot it reports,
 * and the cache is never allowed to violate that.
 */
class BoundedStalenessPropertyTest {

    sealed interface Op {
        record Write(int docIdx, int userIdx) implements Op {}
        record Delete(int docIdx, int userIdx) implements Op {}
        record Check(int docIdx, int userIdx, int zookieIdx) implements Op {}
    }

    @Property(tries = 300)
    void boundedStalenessOutcomeIsHonest(
            @ForAll("operationSequences") List<Op> ops,
            @ForAll @IntRange(min = 1, max = 16) int quantum) {

        var store = new InMemoryTupleStore();
        var engine = new CheckEngine(store, Map.of(), new CheckCache(),
                ConsistencyPolicy.withQuantum(quantum));

        ObjectRef[] docs = new ObjectRef[3];
        SubjectRef[] users = new SubjectRef[3];
        for (int i = 0; i < 3; i++) {
            docs[i] = new ObjectRef("doc", "d" + i);
            users[i] = SubjectRef.user("user", "u" + i);
        }

        // Reference: (docIdx:userIdx) -> revision -> active
        Map<String, TreeMap<Long, Boolean>> reference = new HashMap<>();
        List<Zookie> zookies = new ArrayList<>();
        zookies.add(new Zookie(0));

        for (Op op : ops) {
            switch (op) {
                case Op.Write w -> {
                    Zookie z = store.write(docs[w.docIdx()], "viewer", users[w.userIdx()]);
                    zookies.add(z);
                    reference.computeIfAbsent(w.docIdx() + ":" + w.userIdx(), k -> new TreeMap<>())
                            .put(z.revision(), true);
                }
                case Op.Delete d -> {
                    Zookie z = store.delete(docs[d.docIdx()], "viewer", users[d.userIdx()]);
                    zookies.add(z);
                    reference.computeIfAbsent(d.docIdx() + ":" + d.userIdx(), k -> new TreeMap<>())
                            .put(z.revision(), false);
                }
                case Op.Check c -> {
                    Zookie bound = zookies.get(c.zookieIdx() % zookies.size());

                    CheckOutcome outcome = engine.check(docs[c.docIdx()], "viewer", users[c.userIdx()],
                            Consistency.atLeastAsFresh(bound));
                    long t = outcome.evaluatedAtRevision();

                    // (1) bound
                    if (t < bound.revision() || t > store.latestRevision()) {
                        throw new AssertionError("evaluatedAt " + t + " outside [" + bound.revision()
                                + ", " + store.latestRevision() + "]");
                    }

                    // (2) honesty: compare against the reference at exactly t
                    boolean expected = false;
                    TreeMap<Long, Boolean> chain = reference.get(c.docIdx() + ":" + c.userIdx());
                    if (chain != null) {
                        Map.Entry<Long, Boolean> e = chain.floorEntry(t);
                        if (e != null) expected = e.getValue();
                    }
                    if (outcome.granted() != expected) {
                        throw new AssertionError("dishonest result at evaluatedAt " + t
                                + " (bound " + bound.revision() + ") for d" + c.docIdx()
                                + " u" + c.userIdx() + ": engine=" + outcome.granted()
                                + " expected=" + expected);
                    }
                }
            }
        }
    }

    @Provide
    Arbitrary<List<Op>> operationSequences() {
        Arbitrary<Op> write = Arbitraries.integers().between(0, 2).flatMap(d ->
                Arbitraries.integers().between(0, 2).map(u -> (Op) new Op.Write(d, u)));
        Arbitrary<Op> delete = Arbitraries.integers().between(0, 2).flatMap(d ->
                Arbitraries.integers().between(0, 2).map(u -> (Op) new Op.Delete(d, u)));
        Arbitrary<Op> check = Arbitraries.integers().between(0, 2).flatMap(d ->
                Arbitraries.integers().between(0, 2).flatMap(u ->
                        Arbitraries.integers().between(0, 60).map(r -> (Op) new Op.Check(d, u, r))));

        return Arbitraries.frequencyOf(
                Tuple.of(3, write),
                Tuple.of(2, delete),
                Tuple.of(5, check)
        ).list().ofMinSize(5).ofMaxSize(60);
    }
}
