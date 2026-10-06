package dev.zanzibar.engine;

import dev.zanzibar.config.NamespaceConfig;
import dev.zanzibar.config.NamespaceRegistry;
import dev.zanzibar.config.RewriteRule;
import dev.zanzibar.model.ObjectRef;
import dev.zanzibar.model.RelationTuple;
import dev.zanzibar.model.SubjectRef;
import dev.zanzibar.store.TupleStore;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Answers "who has this relation on this object?" as a tree.
 * It walks the same rules and tuples as the check engine, but instead of
 * stopping at the first grant it collects everything it finds.
 */
public class ExpandEngine {

    private final TupleStore store;
    private final NamespaceRegistry namespaces;

    public ExpandEngine(TupleStore store, NamespaceRegistry namespaces) {
        this.store = store;
        this.namespaces = namespaces;
    }

    public UsersetTree expand(ObjectRef resource, String relation, long revision) {
        return expandRelation(resource, relation, revision, new HashSet<>());
    }

    private UsersetTree expandRelation(ObjectRef resource, String relation, long revision,
                                       Set<String> inProgress) {
        String key = resource + "#" + relation;
        if (inProgress.contains(key)) {
            return UsersetTree.leaf(Set.of()); // cycle: nothing new down this branch
        }
        inProgress.add(key);

        UsersetTree tree;
        NamespaceConfig config = namespaces.get(resource.namespace());
        RewriteRule rule = config == null ? null : config.ruleFor(relation);
        if (rule == null) {
            tree = expandDirect(resource, relation, revision, inProgress);
        } else {
            tree = expandRule(rule, resource, relation, revision, inProgress);
        }

        inProgress.remove(key);
        return tree;
    }

    private UsersetTree expandRule(RewriteRule rule, ObjectRef resource, String relation,
                                   long revision, Set<String> inProgress) {
        return switch (rule) {
            case RewriteRule.This self -> expandDirect(resource, relation, revision, inProgress);

            case RewriteRule.ComputedUserset computed ->
                    expandRelation(resource, computed.relation(), revision, inProgress);

            case RewriteRule.TupleToUserset ttu -> {
                List<UsersetTree> children = new ArrayList<>();
                for (RelationTuple link : store.read(resource, ttu.tuplesetRelation(), revision)) {
                    ObjectRef target = link.subject().asObjectRef();
                    children.add(expandRelation(target, ttu.computedRelation(), revision, inProgress));
                }
                yield unionOf(children);
            }

            case RewriteRule.Union union ->
                    unionOf(expandChildren(union.children(), resource, relation, revision, inProgress));

            case RewriteRule.Intersection intersection ->
                    UsersetTree.node("intersection",
                            expandChildren(intersection.children(), resource, relation, revision, inProgress));

            case RewriteRule.Exclusion exclusion ->
                    UsersetTree.node("exclusion", List.of(
                            expandRule(exclusion.base(), resource, relation, revision, inProgress),
                            expandRule(exclusion.subtract(), resource, relation, revision, inProgress)));
        };
    }

    private List<UsersetTree> expandChildren(List<RewriteRule> rules, ObjectRef resource, String relation,
                                             long revision, Set<String> inProgress) {
        List<UsersetTree> children = new ArrayList<>();
        for (RewriteRule rule : rules) {
            children.add(expandRule(rule, resource, relation, revision, inProgress));
        }
        return children;
    }

    private UsersetTree expandDirect(ObjectRef resource, String relation, long revision,
                                     Set<String> inProgress) {
        Set<SubjectRef> direct = new LinkedHashSet<>();
        List<UsersetTree> nested = new ArrayList<>();

        for (RelationTuple tuple : store.read(resource, relation, revision)) {
            SubjectRef subject = tuple.subject();
            if (subject.isUserset()) {
                nested.add(expandRelation(subject.asObjectRef(), subject.relation(), revision, inProgress));
            } else {
                direct.add(subject);
            }
        }

        List<UsersetTree> children = new ArrayList<>();
        children.add(UsersetTree.leaf(direct));
        children.addAll(nested);
        return unionOf(children);
    }

    /** A union node, leaving out branches that found nobody so the tree stays readable. */
    private UsersetTree unionOf(List<UsersetTree> children) {
        List<UsersetTree> nonEmpty = new ArrayList<>();
        for (UsersetTree child : children) {
            boolean empty = child.subjects().isEmpty() && child.children().isEmpty();
            if (!empty) {
                nonEmpty.add(child);
            }
        }
        if (nonEmpty.isEmpty()) {
            return UsersetTree.leaf(Set.of());
        }
        if (nonEmpty.size() == 1) {
            return nonEmpty.get(0);
        }
        return UsersetTree.node("union", nonEmpty);
    }
}
