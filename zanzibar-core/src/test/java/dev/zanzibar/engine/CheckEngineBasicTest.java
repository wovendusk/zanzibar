package dev.zanzibar.engine;

import dev.zanzibar.model.ObjectRef;
import dev.zanzibar.model.SubjectRef;
import dev.zanzibar.model.Zookie;
import dev.zanzibar.store.InMemoryTupleStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M1 tests: direct tuples and group indirection, no rewrite rules.
 */
class CheckEngineBasicTest {

    private InMemoryTupleStore store;
    private CheckEngine engine;

    private final ObjectRef docReadme = new ObjectRef("doc", "readme");
    private final ObjectRef groupEng = new ObjectRef("group", "eng");
    private final ObjectRef groupAll = new ObjectRef("group", "all");
    private final SubjectRef alice = SubjectRef.user("user", "alice");
    private final SubjectRef bob = SubjectRef.user("user", "bob");

    @BeforeEach
    void setUp() {
        store = new InMemoryTupleStore();
        engine = new CheckEngine(store, Map.of());
    }

    private Zookie latest() {
        return new Zookie(store.latestRevision());
    }

    @Test
    void directGrant() {
        store.write(docReadme, "viewer", alice);
        assertTrue(engine.check(docReadme, "viewer", alice, latest()));
    }

    @Test
    void noGrant() {
        assertFalse(engine.check(docReadme, "viewer", alice, latest()));
    }

    @Test
    void revokedGrant() {
        store.write(docReadme, "viewer", alice);
        store.delete(docReadme, "viewer", alice);
        assertFalse(engine.check(docReadme, "viewer", alice, latest()));
    }

    @Test
    void groupIndirection() {
        store.write(docReadme, "viewer", SubjectRef.userset("group", "eng", "member"));
        store.write(groupEng, "member", alice);

        assertTrue(engine.check(docReadme, "viewer", alice, latest()),
                "Alice is viewer via group:eng#member");
        assertFalse(engine.check(docReadme, "viewer", bob, latest()),
                "Bob is not a member of group:eng");
    }

    @Test
    void nestedGroups() {
        store.write(docReadme, "viewer", SubjectRef.userset("group", "all", "member"));
        store.write(groupAll, "member", SubjectRef.userset("group", "eng", "member"));
        store.write(groupEng, "member", alice);

        assertTrue(engine.check(docReadme, "viewer", alice, latest()),
                "Alice is viewer via group:all → group:eng → alice");
    }

    @Test
    void cycleDetection() {
        ObjectRef groupA = new ObjectRef("group", "a");
        ObjectRef groupB = new ObjectRef("group", "b");
        store.write(groupA, "member", SubjectRef.userset("group", "b", "member"));
        store.write(groupB, "member", SubjectRef.userset("group", "a", "member"));

        assertFalse(engine.check(groupA, "member", alice, latest()),
                "Cycle should terminate without granting access");
    }

    @Test
    void directAndGroupCoexist() {
        store.write(docReadme, "viewer", alice);
        store.write(docReadme, "viewer", SubjectRef.userset("group", "eng", "member"));
        store.write(groupEng, "member", bob);

        assertTrue(engine.check(docReadme, "viewer", alice, latest()), "Direct grant");
        assertTrue(engine.check(docReadme, "viewer", bob, latest()), "Group grant");
    }
}
