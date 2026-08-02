package dev.zanzibar.consistency;

import dev.zanzibar.cache.CheckCache;
import dev.zanzibar.config.NamespaceConfig;
import dev.zanzibar.config.RewriteRule;
import dev.zanzibar.engine.CheckEngine;
import dev.zanzibar.model.ObjectRef;
import dev.zanzibar.model.SubjectRef;
import dev.zanzibar.model.Zookie;
import dev.zanzibar.store.InMemoryTupleStore;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exhaustive differential test of the whole stack: rewrite rules + cache +
 * bounded-staleness modes + replication jitter, all at once.
 *
 * The invariant is unimpeachable and needs no reference model: two engines over
 * the same store, one with the cache and one without (same policy, so both resolve
 * the same evaluation revision), must ALWAYS agree — on the answer and on the
 * revision they evaluated at. Any disagreement is a cache-soundness bug.
 *
 * The rewrite config exercises every interesting path: direct grants, role
 * hierarchy (owner ⊂ editor ⊂ viewer), multi-level folder inheritance
 * (tuple-to-userset), and nested group indirection.
 */
class ExhaustiveDifferentialTest {

    private record Tup(ObjectRef object, String relation, SubjectRef subject) {}
    private record Chk(ObjectRef object, String relation, SubjectRef subject) {}

    private Map<String, NamespaceConfig> configs() {
        var doc = NamespaceConfig.builder("doc")
                .relation("owner", RewriteRule.thisRelation())
                .relation("editor", RewriteRule.union(
                        RewriteRule.thisRelation(),
                        RewriteRule.computedUserset("owner")))
                .relation("viewer", RewriteRule.union(
                        RewriteRule.thisRelation(),
                        RewriteRule.computedUserset("editor"),
                        RewriteRule.tupleToUserset("parent", "viewer")))
                .relation("parent", RewriteRule.thisRelation())
                .build();
        var folder = NamespaceConfig.builder("folder")
                .relation("viewer", RewriteRule.union(
                        RewriteRule.thisRelation(),
                        RewriteRule.tupleToUserset("parent", "viewer")))
                .relation("parent", RewriteRule.thisRelation())
                .build();
        var group = NamespaceConfig.builder("group")
                .relation("member", RewriteRule.thisRelation())
                .build();
        return Map.of("doc", doc, "folder", folder, "group", group);
    }

    private Tup[] candidateTuples() {
        ObjectRef d0 = new ObjectRef("doc", "d0"), d1 = new ObjectRef("doc", "d1");
        ObjectRef f0 = new ObjectRef("folder", "f0"), f1 = new ObjectRef("folder", "f1");
        ObjectRef g0 = new ObjectRef("group", "g0"), g1 = new ObjectRef("group", "g1");
        SubjectRef u0 = SubjectRef.user("user", "u0"),
                u1 = SubjectRef.user("user", "u1"),
                u2 = SubjectRef.user("user", "u2");
        return new Tup[]{
                new Tup(d0, "owner", u0),
                new Tup(d0, "editor", u1),
                new Tup(d0, "viewer", SubjectRef.userset("group", "g0", "member")),
                new Tup(d0, "parent", SubjectRef.user("folder", "f0")),
                new Tup(d1, "viewer", u1),
                new Tup(d1, "parent", SubjectRef.user("folder", "f1")),
                new Tup(f0, "viewer", u0),
                new Tup(f0, "parent", SubjectRef.user("folder", "f1")),
                new Tup(f1, "viewer", u2),
                new Tup(g0, "member", u2),
                new Tup(g0, "member", SubjectRef.userset("group", "g1", "member")),
                new Tup(g1, "member", u0),
        };
    }

    private Chk[] candidateChecks() {
        ObjectRef d0 = new ObjectRef("doc", "d0"), d1 = new ObjectRef("doc", "d1");
        ObjectRef f0 = new ObjectRef("folder", "f0");
        ObjectRef g0 = new ObjectRef("group", "g0");
        SubjectRef u0 = SubjectRef.user("user", "u0"),
                u1 = SubjectRef.user("user", "u1"),
                u2 = SubjectRef.user("user", "u2");
        return new Chk[]{
                new Chk(d0, "viewer", u0), new Chk(d0, "viewer", u1), new Chk(d0, "viewer", u2),
                new Chk(d0, "editor", u0), new Chk(d0, "editor", u1),
                new Chk(d1, "viewer", u1), new Chk(d1, "viewer", u2),
                new Chk(f0, "viewer", u0), new Chk(f0, "viewer", u2),
                new Chk(g0, "member", u0), new Chk(g0, "member", u2),
        };
    }

    @Test
    void cacheNeverChangesAnswer_acrossRewritesModesAndReplicationJitter() {
        Tup[] tuples = candidateTuples();
        Chk[] checks = candidateChecks();
        var cfg = configs();

        int seeds = 120;
        int opsPerSeed = 90;

        for (int seed = 0; seed < seeds; seed++) {
            final int fSeed = seed;
            var store = new InMemoryTupleStore();
            long quantum = 1 + (seed % 8);
            var policy = ConsistencyPolicy.withQuantum(quantum);
            var cached = new CheckEngine(store, cfg, new CheckCache(), policy);
            var uncached = new CheckEngine(store, cfg, null, policy);

            Random rng = new Random(seed);
            List<Zookie> zookies = new ArrayList<>();
            zookies.add(new Zookie(0));

            for (int op = 0; op < opsPerSeed; op++) {
                int roll = rng.nextInt(100);
                if (roll < 55) {
                    // mutate a random tuple
                    Tup t = tuples[rng.nextInt(tuples.length)];
                    Zookie z = rng.nextBoolean()
                            ? store.write(t.object(), t.relation(), t.subject())
                            : store.delete(t.object(), t.relation(), t.subject());
                    zookies.add(z);
                } else if (roll < 63) {
                    // replication jitter: open / close / partially advance the safe window
                    switch (rng.nextInt(3)) {
                        case 0 -> store.pauseReplication();
                        case 1 -> store.resumeReplication();
                        default -> store.advanceSafeRevisionTo(rng.nextInt((int) store.latestRevision() + 1));
                    }
                } else {
                    // a check under a random consistency mode
                    Chk c = checks[rng.nextInt(checks.length)];
                    Consistency mode = randomMode(rng, zookies);

                    CheckOutcome expected = uncached.check(c.object(), c.relation(), c.subject(), mode);
                    CheckOutcome actual = cached.check(c.object(), c.relation(), c.subject(), mode);

                    assertEquals(expected.evaluatedAtRevision(), actual.evaluatedAtRevision(),
                            () -> "eval revision diverged seed=" + fSeed + " q=" + quantum);
                    assertEquals(expected.granted(), actual.granted(),
                            () -> "cache changed the answer: seed=" + fSeed + " q=" + quantum
                                    + " check=" + c + " mode=" + mode
                                    + " uncached=" + expected.granted() + " cached=" + actual.granted());
                }
            }
        }
    }

    private Consistency randomMode(Random rng, List<Zookie> zookies) {
        Zookie bound = zookies.get(rng.nextInt(zookies.size()));
        return switch (rng.nextInt(4)) {
            case 0 -> Consistency.atLeastAsFresh(bound);
            case 1 -> Consistency.atExactSnapshot(bound);
            case 2 -> Consistency.minimizeLatency();
            default -> Consistency.fullyConsistent();
        };
    }
}
