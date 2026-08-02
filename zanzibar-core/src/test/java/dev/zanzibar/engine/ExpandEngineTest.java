package dev.zanzibar.engine;

import dev.zanzibar.config.NamespaceConfig;
import dev.zanzibar.config.RewriteRule;
import dev.zanzibar.model.ObjectRef;
import dev.zanzibar.model.SubjectRef;
import dev.zanzibar.store.InMemoryTupleStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ExpandEngineTest {

    private InMemoryTupleStore store;
    private ExpandEngine expandEngine;

    private final SubjectRef alice = SubjectRef.user("user", "alice");
    private final SubjectRef bob = SubjectRef.user("user", "bob");

    @BeforeEach
    void setUp() {
        store = new InMemoryTupleStore();
    }

    @Test
    void expandDirectRelation() {
        expandEngine = new ExpandEngine(store, Map.of());

        ObjectRef doc = new ObjectRef("doc", "readme");
        store.write(doc, "viewer", alice);
        store.write(doc, "viewer", bob);

        UsersetTree tree = expandEngine.expand(doc, "viewer", Long.MAX_VALUE);
        Set<SubjectRef> subjects = collectLeaves(tree);

        assertTrue(subjects.contains(alice));
        assertTrue(subjects.contains(bob));
    }

    @Test
    void expandWithComputedUserset() {
        var docConfig = NamespaceConfig.builder("doc")
                .relation("owner", RewriteRule.thisRelation())
                .relation("viewer", RewriteRule.union(
                        RewriteRule.thisRelation(),
                        RewriteRule.computedUserset("owner")))
                .build();

        expandEngine = new ExpandEngine(store, Map.of("doc", docConfig));

        ObjectRef doc = new ObjectRef("doc", "readme");
        store.write(doc, "owner", alice);
        store.write(doc, "viewer", bob);

        UsersetTree tree = expandEngine.expand(doc, "viewer", Long.MAX_VALUE);
        Set<SubjectRef> subjects = collectLeaves(tree);

        assertTrue(subjects.contains(alice), "Owner should appear as viewer via computed_userset");
        assertTrue(subjects.contains(bob), "Direct viewer should appear");
    }

    @Test
    void expandWithInheritance() {
        var folderConfig = NamespaceConfig.builder("folder")
                .relation("viewer", RewriteRule.thisRelation())
                .build();

        var docConfig = NamespaceConfig.builder("doc")
                .relation("parent", RewriteRule.thisRelation())
                .relation("viewer", RewriteRule.union(
                        RewriteRule.thisRelation(),
                        RewriteRule.tupleToUserset("parent", "viewer")))
                .build();

        expandEngine = new ExpandEngine(store, Map.of("doc", docConfig, "folder", folderConfig));

        ObjectRef folder = new ObjectRef("folder", "eng");
        ObjectRef doc = new ObjectRef("doc", "readme");

        store.write(folder, "viewer", alice);
        store.write(doc, "parent", SubjectRef.user("folder", "eng"));

        UsersetTree tree = expandEngine.expand(doc, "viewer", Long.MAX_VALUE);
        Set<SubjectRef> subjects = collectLeaves(tree);

        assertTrue(subjects.contains(alice), "Inherited viewer should appear");
    }

    @Test
    void expandHandlesCycle() {
        var groupConfig = NamespaceConfig.builder("group")
                .relation("member", RewriteRule.thisRelation())
                .build();

        expandEngine = new ExpandEngine(store, Map.of("group", groupConfig));

        ObjectRef groupA = new ObjectRef("group", "a");
        ObjectRef groupB = new ObjectRef("group", "b");

        store.write(groupA, "member", SubjectRef.userset("group", "b", "member"));
        store.write(groupB, "member", SubjectRef.userset("group", "a", "member"));

        assertDoesNotThrow(() -> expandEngine.expand(groupA, "member", Long.MAX_VALUE),
                "Cyclic groups should not cause infinite recursion");
    }

    private Set<SubjectRef> collectLeaves(UsersetTree tree) {
        Set<SubjectRef> result = new HashSet<>();
        collectLeavesRecursive(tree, result);
        return result;
    }

    private void collectLeavesRecursive(UsersetTree tree, Set<SubjectRef> acc) {
        switch (tree) {
            case UsersetTree.Leaf leaf -> acc.addAll(leaf.subjects());
            case UsersetTree.Intermediate intermediate ->
                    intermediate.children().forEach(child -> collectLeavesRecursive(child, acc));
        }
    }
}
