package dev.zanzibar;

import dev.zanzibar.model.ObjectRef;
import dev.zanzibar.model.SubjectRef;
import dev.zanzibar.model.Zookie;
import dev.zanzibar.store.InMemoryTupleStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryTupleStoreTest {

    private static final ObjectRef README = new ObjectRef("doc", "readme");
    private static final SubjectRef ALICE = SubjectRef.user("user", "alice");

    @Test
    void revisionsIncreaseByOne() {
        InMemoryTupleStore store = new InMemoryTupleStore();
        assertEquals(0, store.latestRevision());
        assertEquals(1, store.write(README, "viewer", ALICE).revision());
        assertEquals(2, store.delete(README, "viewer", ALICE).revision());
        assertEquals(2, store.latestRevision());
    }

    @Test
    void everyOldSnapshotStaysReadable() {
        InMemoryTupleStore store = new InMemoryTupleStore();
        Zookie written = store.write(README, "viewer", ALICE);
        Zookie deleted = store.delete(README, "viewer", ALICE);
        Zookie rewritten = store.write(README, "viewer", ALICE);

        assertFalse(store.exists(README, "viewer", ALICE, 0));
        assertTrue(store.exists(README, "viewer", ALICE, written.revision()));
        assertFalse(store.exists(README, "viewer", ALICE, deleted.revision()));
        assertTrue(store.exists(README, "viewer", ALICE, rewritten.revision()));

        assertEquals(1, store.read(README, "viewer", written.revision()).size());
        assertEquals(0, store.read(README, "viewer", deleted.revision()).size());
    }
}
