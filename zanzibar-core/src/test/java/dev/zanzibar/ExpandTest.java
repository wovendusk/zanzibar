package dev.zanzibar;

import dev.zanzibar.engine.UsersetTree;
import dev.zanzibar.model.ObjectRef;
import dev.zanzibar.model.SubjectRef;
import dev.zanzibar.store.InMemoryTupleStore;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExpandTest {

    /** Collects every subject in a tree whose nodes are all unions. */
    private void collect(UsersetTree tree, Set<SubjectRef> into) {
        into.addAll(tree.subjects());
        for (UsersetTree child : tree.children()) {
            collect(child, into);
        }
    }

    @Test
    void expandFindsDirectGroupAndInheritedViewers() {
        ZanzibarEngine engine = new ZanzibarEngine(new InMemoryTupleStore(), TestNamespaces.standard(), 1);
        ObjectRef readme = new ObjectRef("doc", "readme");
        SubjectRef alice = SubjectRef.user("user", "alice");
        SubjectRef bob = SubjectRef.user("user", "bob");
        SubjectRef carol = SubjectRef.user("user", "carol");

        engine.write(readme, "viewer", alice);
        engine.write(new ObjectRef("group", "eng"), "member", bob);
        engine.write(readme, "viewer", SubjectRef.userset("group", "eng", "member"));
        engine.write(readme, "parent", SubjectRef.user("folder", "eng"));
        engine.write(new ObjectRef("folder", "eng"), "viewer", carol);

        UsersetTree tree = engine.expand(readme, "viewer", null);
        assertEquals("union", tree.operation());

        Set<SubjectRef> found = new HashSet<>();
        collect(tree, found);
        assertEquals(Set.of(alice, bob, carol), found);
    }
}
