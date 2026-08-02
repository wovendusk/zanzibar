package dev.zanzibar.leopard;

import dev.zanzibar.config.NamespaceConfig;
import dev.zanzibar.config.RewriteRule;
import dev.zanzibar.engine.CheckEngine;
import dev.zanzibar.model.ObjectRef;
import dev.zanzibar.model.SubjectRef;
import dev.zanzibar.model.Zookie;
import dev.zanzibar.store.InMemoryTupleStore;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The headline correctness test for Leopard: it must agree with the authoritative
 * recursive engine on <em>every</em> membership query, across random nested-group
 * graphs (including cycles) built up and torn down by random writes and deletes.
 *
 * The engine is the oracle — the same tuples drive both the store (which the engine
 * reads) and the index. After every mutation, the flattened index and the recursive
 * walk must return the identical answer for all (user, group) pairs.
 */
class LeopardDifferentialTest {

    private static final int GROUPS = 6;
    private static final int USERS = 4;

    @Test
    void leopardAgreesWithRecursiveEngine_underRandomWritesAndDeletes() {
        var groupConfig = NamespaceConfig.builder("group")
                .relation("member", RewriteRule.thisRelation())
                .build();
        Map<String, NamespaceConfig> configs = Map.of("group", groupConfig);

        ObjectRef[] groups = new ObjectRef[GROUPS];
        for (int i = 0; i < GROUPS; i++) groups[i] = new ObjectRef("group", "g" + i);
        SubjectRef[] users = new SubjectRef[USERS];
        for (int i = 0; i < USERS; i++) users[i] = SubjectRef.user("user", "u" + i);

        for (int seed = 0; seed < 40; seed++) {
            var store = new InMemoryTupleStore();
            var engine = new CheckEngine(store, configs); // authoritative oracle (no cache)
            var index = new LeopardIndex("group", "member");
            Random rng = new Random(seed);

            for (int op = 0; op < 45; op++) {
                int gi = rng.nextInt(GROUPS);
                ObjectRef group = groups[gi];

                // Either a direct user member, or a subgroup edge (group contains group).
                SubjectRef subject;
                if (rng.nextInt(100) < 40) {
                    subject = users[rng.nextInt(USERS)];
                } else {
                    int gk = rng.nextInt(GROUPS);
                    if (gk == gi) continue; // skip trivial self-loop
                    subject = SubjectRef.userset("group", "g" + gk, "member");
                }

                long rev;
                if (rng.nextBoolean()) {
                    rev = store.write(group, "member", subject).revision();
                    index.applyWrite(group, "member", subject, rev);
                } else {
                    rev = store.delete(group, "member", subject).revision();
                    index.applyDelete(group, "member", subject, rev);
                }

                // Differential check: every (user, group) pair must agree.
                Zookie now = new Zookie(store.latestRevision());
                for (SubjectRef u : users) {
                    for (ObjectRef g : groups) {
                        boolean expected = engine.check(g, "member", u, now);
                        boolean actual = index.isMember(u, g);
                        final int fSeed = seed, fOp = op;
                        assertEquals(expected, actual,
                                () -> "seed=" + fSeed + " op=" + fOp + " check " + u + " in " + g
                                        + ": engine=" + expected + " leopard=" + actual);
                    }
                }
            }
        }
    }
}
