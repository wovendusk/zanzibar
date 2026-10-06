package dev.zanzibar;

import dev.zanzibar.model.ObjectRef;
import dev.zanzibar.model.SubjectRef;
import dev.zanzibar.model.Zookie;
import dev.zanzibar.store.InMemoryTupleStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CheckTest {

    private static final ObjectRef README = new ObjectRef("doc", "readme");
    private static final SubjectRef ALICE = SubjectRef.user("user", "alice");
    private static final SubjectRef BOB = SubjectRef.user("user", "bob");

    private ZanzibarEngine engine;

    @BeforeEach
    void setUp() {
        engine = new ZanzibarEngine(new InMemoryTupleStore(), TestNamespaces.standard(), 1);
    }

    private boolean check(ObjectRef resource, String relation, SubjectRef subject) {
        return engine.check(resource, relation, subject, null).granted();
    }

    @Test
    void directTupleGrants() {
        engine.write(README, "viewer", ALICE);
        assertTrue(check(README, "viewer", ALICE));
        assertFalse(check(README, "viewer", BOB));
    }

    @Test
    void deleteRevokes() {
        engine.write(README, "viewer", ALICE);
        engine.delete(README, "viewer", ALICE);
        assertFalse(check(README, "viewer", ALICE));
    }

    @Test
    void computedUsersetMakesOwnersEditorsAndViewers() {
        engine.write(README, "owner", ALICE);
        assertTrue(check(README, "editor", ALICE));
        assertTrue(check(README, "viewer", ALICE));
        assertFalse(check(README, "owner", BOB));
    }

    @Test
    void groupMembersGetWhatTheGroupWasGranted() {
        engine.write(new ObjectRef("group", "eng"), "member", ALICE);
        engine.write(README, "viewer", SubjectRef.userset("group", "eng", "member"));
        assertTrue(check(README, "viewer", ALICE));
        assertFalse(check(README, "viewer", BOB));
    }

    @Test
    void nestedGroupsAreFollowed() {
        engine.write(new ObjectRef("group", "backend"), "member", ALICE);
        engine.write(new ObjectRef("group", "eng"), "member", SubjectRef.userset("group", "backend", "member"));
        engine.write(new ObjectRef("group", "staff"), "member", SubjectRef.userset("group", "eng", "member"));
        engine.write(README, "viewer", SubjectRef.userset("group", "staff", "member"));
        assertTrue(check(README, "viewer", ALICE));
    }

    @Test
    void viewersOfAParentFolderCanViewTheDocument() {
        ObjectRef eng = new ObjectRef("folder", "eng");
        ObjectRef root = new ObjectRef("folder", "root");
        engine.write(README, "parent", SubjectRef.user("folder", "eng"));
        engine.write(eng, "parent", SubjectRef.user("folder", "root"));
        engine.write(root, "viewer", ALICE);
        assertTrue(check(README, "viewer", ALICE));
        assertFalse(check(README, "viewer", BOB));
    }

    @Test
    void exclusionRemovesBannedUsers() {
        engine.write(README, "viewer", ALICE);
        engine.write(README, "viewer", BOB);
        engine.write(README, "banned", BOB);
        assertTrue(check(README, "reader", ALICE));
        assertFalse(check(README, "reader", BOB));
    }

    @Test
    void intersectionNeedsEveryBranch() {
        engine.write(README, "viewer", ALICE);
        engine.write(README, "viewer", BOB);
        engine.write(README, "cleared", ALICE);
        assertTrue(check(README, "auditor", ALICE));
        assertFalse(check(README, "auditor", BOB));
    }

    @Test
    void groupsThatContainEachOtherDoNotLoopForever() {
        engine.write(new ObjectRef("group", "a"), "member", SubjectRef.userset("group", "b", "member"));
        engine.write(new ObjectRef("group", "b"), "member", SubjectRef.userset("group", "a", "member"));
        engine.write(new ObjectRef("group", "b"), "member", ALICE);
        assertTrue(check(new ObjectRef("group", "a"), "member", ALICE));
        assertFalse(check(new ObjectRef("group", "a"), "member", BOB));
    }

    @Test
    void cachedAnswersAreNotReusedAfterARevoke() {
        engine.write(README, "viewer", ALICE);
        assertTrue(check(README, "viewer", ALICE));
        assertTrue(check(README, "viewer", ALICE)); // second time served from the cache
        assertTrue(engine.cache().hitCount() > 0);

        engine.delete(README, "viewer", ALICE);
        assertFalse(check(README, "viewer", ALICE));
    }

    /**
     * The "new enemy" problem. Bob is removed, and afterwards new content is
     * added. With a quantum of 10 a check without a zookie may run at an old
     * snapshot in which Bob still had access. Checking with the zookie of the
     * new content forces a snapshot that includes the removal.
     */
    @Test
    void zookieStopsARevokedUserFromSeeingNewContent() {
        ZanzibarEngine coarse = new ZanzibarEngine(new InMemoryTupleStore(), TestNamespaces.standard(), 10);
        for (int i = 0; i < 9; i++) {
            coarse.write(new ObjectRef("doc", "filler" + i), "viewer", ALICE);
        }
        coarse.write(README, "viewer", BOB);                       // revision 10
        coarse.delete(README, "viewer", BOB);                      // revision 11: Bob removed
        Zookie contentSaved = coarse.write(README, "editor", ALICE); // revision 12: content changes

        // Without a zookie the check runs at revision 10 and still sees Bob.
        assertTrue(coarse.check(README, "viewer", BOB, null).granted());

        // With the content's zookie it runs at revision 12 or later.
        var result = coarse.check(README, "viewer", BOB, contentSaved);
        assertFalse(result.granted());
        assertTrue(result.evaluatedAtRevision() >= contentSaved.revision());
    }

    @Test
    void aZookieFromTheFutureIsRejected() {
        engine.write(README, "viewer", ALICE);
        assertThrows(IllegalArgumentException.class,
                () -> engine.check(README, "viewer", ALICE, new Zookie(99)));
    }

    @Test
    void traceRecordsTheStepsAndTheResult() {
        engine.write(new ObjectRef("group", "eng"), "member", ALICE);
        engine.write(README, "viewer", SubjectRef.userset("group", "eng", "member"));
        var result = engine.checkWithTrace(README, "viewer", ALICE, null);
        assertTrue(result.granted());
        assertNotNull(result.trace());
        assertTrue(result.trace().contains("group:eng#member"));
        assertTrue(result.trace().contains("RESULT"));
    }
}
