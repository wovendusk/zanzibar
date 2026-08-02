package dev.zanzibar.engine;

import dev.zanzibar.config.NamespaceConfig;
import dev.zanzibar.config.RewriteRule;
import dev.zanzibar.model.ObjectRef;
import dev.zanzibar.model.SubjectRef;
import dev.zanzibar.model.Zookie;
import dev.zanzibar.store.InMemoryTupleStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M2 tests: userset rewrites — role hierarchies, folder inheritance,
 * intersection, exclusion.
 */
class CheckEngineRewriteTest {

    private InMemoryTupleStore store;
    private CheckEngine engine;

    private final SubjectRef alice = SubjectRef.user("user", "alice");
    private final SubjectRef bob = SubjectRef.user("user", "bob");
    private final SubjectRef charlie = SubjectRef.user("user", "charlie");

    @BeforeEach
    void setUp() {
        store = new InMemoryTupleStore();
    }

    private Zookie latest() {
        return new Zookie(store.latestRevision());
    }

    @Test
    void roleHierarchy_ownerIsAlsoViewer() {
        var docConfig = NamespaceConfig.builder("doc")
                .relation("owner", RewriteRule.thisRelation())
                .relation("editor", RewriteRule.union(
                        RewriteRule.thisRelation(),
                        RewriteRule.computedUserset("owner")))
                .relation("viewer", RewriteRule.union(
                        RewriteRule.thisRelation(),
                        RewriteRule.computedUserset("editor")))
                .build();

        engine = new CheckEngine(store, Map.of("doc", docConfig));

        ObjectRef doc = new ObjectRef("doc", "readme");
        store.write(doc, "owner", alice);

        assertTrue(engine.check(doc, "owner", alice, latest()));
        assertTrue(engine.check(doc, "editor", alice, latest()), "Owner is also editor");
        assertTrue(engine.check(doc, "viewer", alice, latest()), "Owner is also viewer");
        assertFalse(engine.check(doc, "viewer", bob, latest()));
    }

    @Test
    void folderInheritance() {
        var folderConfig = NamespaceConfig.builder("folder")
                .relation("viewer", RewriteRule.thisRelation())
                .build();

        var docConfig = NamespaceConfig.builder("doc")
                .relation("parent", RewriteRule.thisRelation())
                .relation("viewer", RewriteRule.union(
                        RewriteRule.thisRelation(),
                        RewriteRule.tupleToUserset("parent", "viewer")))
                .build();

        engine = new CheckEngine(store, Map.of("folder", folderConfig, "doc", docConfig));

        ObjectRef folder = new ObjectRef("folder", "eng");
        ObjectRef doc = new ObjectRef("doc", "readme");

        store.write(folder, "viewer", alice);
        store.write(doc, "parent", SubjectRef.user("folder", "eng"));

        assertTrue(engine.check(doc, "viewer", alice, latest()),
                "Alice inherits viewer from parent folder");
        assertFalse(engine.check(doc, "viewer", bob, latest()));
    }

    @Test
    void deepFolderHierarchy() {
        var folderConfig = NamespaceConfig.builder("folder")
                .relation("parent", RewriteRule.thisRelation())
                .relation("viewer", RewriteRule.union(
                        RewriteRule.thisRelation(),
                        RewriteRule.tupleToUserset("parent", "viewer")))
                .build();

        engine = new CheckEngine(store, Map.of("folder", folderConfig));

        ObjectRef root = new ObjectRef("folder", "root");
        ObjectRef dept = new ObjectRef("folder", "dept");
        ObjectRef team = new ObjectRef("folder", "team");

        store.write(root, "viewer", alice);
        store.write(dept, "parent", SubjectRef.user("folder", "root"));
        store.write(team, "parent", SubjectRef.user("folder", "dept"));

        assertTrue(engine.check(team, "viewer", alice, latest()),
                "Alice inherits through 3 levels: root → dept → team");
    }

    @Test
    void intersection_requiresBothConditions() {
        var docConfig = NamespaceConfig.builder("doc")
                .relation("team_member", RewriteRule.thisRelation())
                .relation("has_clearance", RewriteRule.thisRelation())
                .relation("viewer", RewriteRule.intersection(
                        RewriteRule.computedUserset("team_member"),
                        RewriteRule.computedUserset("has_clearance")))
                .build();

        engine = new CheckEngine(store, Map.of("doc", docConfig));
        ObjectRef doc = new ObjectRef("doc", "classified");

        store.write(doc, "team_member", alice);
        store.write(doc, "has_clearance", alice);
        store.write(doc, "team_member", bob);

        assertTrue(engine.check(doc, "viewer", alice, latest()),
                "Alice has both team_member AND has_clearance");
        assertFalse(engine.check(doc, "viewer", bob, latest()),
                "Bob only has team_member, not has_clearance");
    }

    @Test
    void exclusion_bannedUserDenied() {
        var docConfig = NamespaceConfig.builder("doc")
                .relation("viewer", RewriteRule.exclusion(
                        RewriteRule.thisRelation(),
                        RewriteRule.computedUserset("banned")))
                .relation("banned", RewriteRule.thisRelation())
                .build();

        engine = new CheckEngine(store, Map.of("doc", docConfig));
        ObjectRef doc = new ObjectRef("doc", "readme");

        store.write(doc, "viewer", alice);
        store.write(doc, "viewer", bob);
        store.write(doc, "banned", bob);

        assertTrue(engine.check(doc, "viewer", alice, latest()));
        assertFalse(engine.check(doc, "viewer", bob, latest()),
                "Bob is a viewer but also banned — exclusion denies access");
    }

    @Test
    void combinedRolesAndInheritance() {
        var folderConfig = NamespaceConfig.builder("folder")
                .relation("viewer", RewriteRule.thisRelation())
                .build();

        var docConfig = NamespaceConfig.builder("doc")
                .relation("parent", RewriteRule.thisRelation())
                .relation("owner", RewriteRule.thisRelation())
                .relation("editor", RewriteRule.union(
                        RewriteRule.thisRelation(),
                        RewriteRule.computedUserset("owner")))
                .relation("viewer", RewriteRule.union(
                        RewriteRule.thisRelation(),
                        RewriteRule.computedUserset("editor"),
                        RewriteRule.tupleToUserset("parent", "viewer")))
                .build();

        engine = new CheckEngine(store, Map.of("doc", docConfig, "folder", folderConfig));

        ObjectRef folder = new ObjectRef("folder", "eng");
        ObjectRef doc = new ObjectRef("doc", "spec");

        store.write(doc, "owner", alice);
        store.write(folder, "viewer", bob);
        store.write(doc, "parent", SubjectRef.user("folder", "eng"));
        store.write(doc, "viewer", charlie);

        assertTrue(engine.check(doc, "viewer", alice, latest()), "Owner → editor → viewer");
        assertTrue(engine.check(doc, "viewer", bob, latest()), "Folder viewer → doc viewer");
        assertTrue(engine.check(doc, "viewer", charlie, latest()), "Direct viewer");
    }
}
