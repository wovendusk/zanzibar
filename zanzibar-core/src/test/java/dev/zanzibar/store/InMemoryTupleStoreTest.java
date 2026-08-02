package dev.zanzibar.store;

import dev.zanzibar.model.ObjectRef;
import dev.zanzibar.model.RelationTuple;
import dev.zanzibar.model.SubjectRef;
import dev.zanzibar.model.Zookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryTupleStoreTest {

    private InMemoryTupleStore store;

    private final ObjectRef doc = new ObjectRef("doc", "readme");
    private final SubjectRef alice = SubjectRef.user("user", "alice");
    private final SubjectRef bob = SubjectRef.user("user", "bob");
    private final SubjectRef engMembers = SubjectRef.userset("group", "eng", "member");

    @BeforeEach
    void setUp() {
        store = new InMemoryTupleStore();
    }

    @Test
    void writeAndReadBack() {
        store.write(doc, "viewer", alice);
        List<RelationTuple> tuples = store.read(doc, "viewer", Long.MAX_VALUE);
        assertEquals(1, tuples.size());
        assertEquals(alice, tuples.get(0).subject());
    }

    @Test
    void writeReturnsIncreasingRevisions() {
        Zookie z1 = store.write(doc, "viewer", alice);
        Zookie z2 = store.write(doc, "editor", bob);
        assertTrue(z2.revision() > z1.revision());
    }

    @Test
    void existsReturnsTrueForWrittenTuple() {
        store.write(doc, "viewer", alice);
        assertTrue(store.exists(doc, "viewer", alice, Long.MAX_VALUE));
    }

    @Test
    void existsReturnsFalseForMissingTuple() {
        assertFalse(store.exists(doc, "viewer", alice, Long.MAX_VALUE));
    }

    @Test
    void deleteRemovesTuple() {
        store.write(doc, "viewer", alice);
        store.delete(doc, "viewer", alice);
        assertFalse(store.exists(doc, "viewer", alice, Long.MAX_VALUE));
        assertTrue(store.read(doc, "viewer", Long.MAX_VALUE).isEmpty());
    }

    @Test
    void multipleSubjectsForSameRelation() {
        store.write(doc, "viewer", alice);
        store.write(doc, "viewer", bob);
        store.write(doc, "viewer", engMembers);

        List<RelationTuple> tuples = store.read(doc, "viewer", Long.MAX_VALUE);
        assertEquals(3, tuples.size());
    }

    @Test
    void snapshotReadSeesOlderState() {
        Zookie z1 = store.write(doc, "viewer", alice);
        store.delete(doc, "viewer", alice);

        assertTrue(store.exists(doc, "viewer", alice, z1.revision()),
                "Tuple should be visible at the write revision");
        assertFalse(store.exists(doc, "viewer", alice, Long.MAX_VALUE),
                "Tuple should be invisible after tombstone");
    }

    @Test
    void rewriteAfterDeleteMakesTupleActiveAgain() {
        store.write(doc, "viewer", alice);
        store.delete(doc, "viewer", alice);
        store.write(doc, "viewer", alice);

        assertTrue(store.exists(doc, "viewer", alice, Long.MAX_VALUE));
    }

    @Test
    void readAtRevisionBeforeWriteReturnsEmpty() {
        long before = store.latestRevision();
        store.write(doc, "viewer", alice);

        assertTrue(store.read(doc, "viewer", before).isEmpty());
    }

    @Test
    void latestRevisionAdvancesOnWriteAndDelete() {
        assertEquals(0, store.latestRevision());
        store.write(doc, "viewer", alice);
        assertEquals(1, store.latestRevision());
        store.delete(doc, "viewer", alice);
        assertEquals(2, store.latestRevision());
    }
}
