package dev.zanzibar.property;

import dev.zanzibar.cache.CheckCache;
import dev.zanzibar.engine.CheckEngine;
import dev.zanzibar.model.ObjectRef;
import dev.zanzibar.model.SubjectRef;
import dev.zanzibar.model.Zookie;
import dev.zanzibar.store.InMemoryTupleStore;
import net.jqwik.api.*;

import java.util.*;

/**
 * Property-based test using jqwik.
 * Generates random sequences of write/delete/check operations and
 * verifies the engine always matches a trivial reference model.
 */
class ConsistencyPropertyTest {

    sealed interface Op {
        record Write(int docIdx, int userIdx) implements Op {}
        record Delete(int docIdx, int userIdx) implements Op {}
        record Check(int docIdx, int userIdx, int atRevisionOffset) implements Op {}
    }

    @Property(tries = 200)
    void engineMatchesReferenceModel(@ForAll("operationSequences") List<Op> ops) {
        var store = new InMemoryTupleStore();
        // Cache ENABLED: this property is the guard for cache/engine consistency.
        // With the cache off (as it originally was), the cache path went untested
        // and a revision-aware staleness bug slipped through — see CacheConsistencyTest.
        var engine = new CheckEngine(store, Map.of(), new CheckCache());

        ObjectRef[] docs = new ObjectRef[3];
        SubjectRef[] users = new SubjectRef[3];
        for (int i = 0; i < 3; i++) {
            docs[i] = new ObjectRef("doc", "d" + i);
            users[i] = SubjectRef.user("user", "u" + i);
        }

        // Reference model: revision → snapshot of active tuples
        // For simplicity, track (docIdx, userIdx) → list of (revision, isActive)
        Map<String, TreeMap<Long, Boolean>> reference = new HashMap<>();
        List<Zookie> zookies = new ArrayList<>();
        zookies.add(new Zookie(0));

        for (Op op : ops) {
            switch (op) {
                case Op.Write w -> {
                    ObjectRef doc = docs[w.docIdx()];
                    SubjectRef user = users[w.userIdx()];
                    Zookie z = store.write(doc, "viewer", user);
                    zookies.add(z);
                    reference.computeIfAbsent(w.docIdx() + ":" + w.userIdx(),
                            k -> new TreeMap<>()).put(z.revision(), true);
                }
                case Op.Delete d -> {
                    ObjectRef doc = docs[d.docIdx()];
                    SubjectRef user = users[d.userIdx()];
                    Zookie z = store.delete(doc, "viewer", user);
                    zookies.add(z);
                    reference.computeIfAbsent(d.docIdx() + ":" + d.userIdx(),
                            k -> new TreeMap<>()).put(z.revision(), false);
                }
                case Op.Check c -> {
                    if (zookies.size() <= 1) continue;
                    int idx = Math.min(c.atRevisionOffset(), zookies.size() - 1);
                    Zookie zookie = zookies.get(Math.max(1, idx));

                    ObjectRef doc = docs[c.docIdx()];
                    SubjectRef user = users[c.userIdx()];
                    boolean engineResult = engine.check(doc, "viewer", user, zookie);

                    // Reference: find the latest entry at or before zookie.revision()
                    String key = c.docIdx() + ":" + c.userIdx();
                    TreeMap<Long, Boolean> chain = reference.get(key);
                    boolean expected = false;
                    if (chain != null) {
                        Map.Entry<Long, Boolean> entry = chain.floorEntry(zookie.revision());
                        if (entry != null) expected = entry.getValue();
                    }

                    if (engineResult != expected) {
                        throw new AssertionError(
                                "Mismatch at zookie " + zookie + " for doc d" + c.docIdx()
                                + " user u" + c.userIdx()
                                + ": engine=" + engineResult + " expected=" + expected);
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
                        Arbitraries.integers().between(0, 20).map(r ->
                                (Op) new Op.Check(d, u, r))));

        return Arbitraries.frequencyOf(
                Tuple.of(3, write),
                Tuple.of(2, delete),
                Tuple.of(5, check)
        ).list().ofMinSize(5).ofMaxSize(50);
    }
}
