package dev.zanzibar;

import dev.zanzibar.leopard.LeopardIndex;
import dev.zanzibar.model.ObjectRef;
import dev.zanzibar.model.SubjectRef;
import dev.zanzibar.store.InMemoryTupleStore;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeopardIndexTest {

    private static final SubjectRef ALICE = SubjectRef.user("user", "alice");

    private ObjectRef group(String name) {
        return new ObjectRef("group", name);
    }

    private SubjectRef membersOf(String name) {
        return SubjectRef.userset("group", name, "member");
    }

    @Test
    void directAndNestedMembership() {
        LeopardIndex index = new LeopardIndex("group", "member");
        index.applyWrite(group("backend"), "member", ALICE, 1);
        index.applyWrite(group("eng"), "member", membersOf("backend"), 2);
        index.applyWrite(group("staff"), "member", membersOf("eng"), 3);

        assertTrue(index.isMember(ALICE, group("backend")));
        assertTrue(index.isMember(ALICE, group("eng")));
        assertTrue(index.isMember(ALICE, group("staff")));
        assertFalse(index.isMember(ALICE, group("sales")));
        assertEquals(3, index.indexedThrough());
    }

    @Test
    void removingANestingEdgeRemovesInheritedMembership() {
        LeopardIndex index = new LeopardIndex("group", "member");
        index.applyWrite(group("backend"), "member", ALICE, 1);
        index.applyWrite(group("eng"), "member", membersOf("backend"), 2);
        index.applyDelete(group("eng"), "member", membersOf("backend"), 3);

        assertTrue(index.isMember(ALICE, group("backend")));
        assertFalse(index.isMember(ALICE, group("eng")));
    }

    @Test
    void tuplesThatAreNotGroupMembershipOnlyAdvanceTheRevision() {
        LeopardIndex index = new LeopardIndex("group", "member");
        index.applyWrite(new ObjectRef("doc", "readme"), "viewer", ALICE, 7);
        assertEquals(7, index.indexedThrough());
        assertFalse(index.isMember(ALICE, new ObjectRef("doc", "readme")));
    }

    @Test
    void applyingTheSameEventTwiceChangesNothing() {
        LeopardIndex index = new LeopardIndex("group", "member");
        index.applyWrite(group("eng"), "member", ALICE, 1);
        index.applyWrite(group("eng"), "member", ALICE, 1);
        assertTrue(index.isMember(ALICE, group("eng")));
        index.applyDelete(group("eng"), "member", ALICE, 2);
        assertFalse(index.isMember(ALICE, group("eng")));
    }

    /**
     * Applies a long random sequence of membership changes to both the index
     * and the real engine, and requires the two to agree on every question
     * after every change.
     */
    @Test
    void indexAgreesWithTheCheckEngineOnRandomChanges() {
        int groups = 6;
        int users = 4;
        Random random = new Random(42);
        ZanzibarEngine engine = new ZanzibarEngine(new InMemoryTupleStore(), TestNamespaces.standard(), 1);
        LeopardIndex index = new LeopardIndex("group", "member");

        for (int step = 0; step < 300; step++) {
            ObjectRef target = group("g" + random.nextInt(groups));
            SubjectRef subject = random.nextBoolean()
                    ? SubjectRef.user("user", "u" + random.nextInt(users))
                    : membersOf("g" + random.nextInt(groups));

            if (random.nextInt(3) == 0) {
                long revision = engine.delete(target, "member", subject).revision();
                index.applyDelete(target, "member", subject, revision);
            } else {
                long revision = engine.write(target, "member", subject).revision();
                index.applyWrite(target, "member", subject, revision);
            }

            for (int g = 0; g < groups; g++) {
                for (int u = 0; u < users; u++) {
                    SubjectRef user = SubjectRef.user("user", "u" + u);
                    boolean expected = engine.check(group("g" + g), "member", user, null).granted();
                    assertEquals(expected, index.isMember(user, group("g" + g)),
                            "step " + step + ": is u" + u + " in g" + g + "?");
                }
            }
        }
    }
}
