package dev.zanzibar.engine;

import dev.zanzibar.config.NamespaceConfig;
import dev.zanzibar.config.RelationConfig;
import dev.zanzibar.config.RewriteRule;
import dev.zanzibar.model.ObjectRef;
import dev.zanzibar.model.RelationTuple;
import dev.zanzibar.model.SubjectRef;
import dev.zanzibar.store.TupleStore;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Computes the full userset tree for a given (resource, relation).
 * Unlike Read (which returns raw tuples), Expand evaluates rewrite rules
 * and returns the computed set of all effective subjects.
 */
public class ExpandEngine {

    private final TupleStore store;
    private final Map<String, NamespaceConfig> configs;

    public ExpandEngine(TupleStore store, Map<String, NamespaceConfig> configs) {
        this.store = Objects.requireNonNull(store);
        this.configs = Objects.requireNonNull(configs);
    }

    public UsersetTree expand(ObjectRef resource, String relation, long maxRevision) {
        return expandInternal(resource, relation, maxRevision, new HashSet<>());
    }

    private UsersetTree expandInternal(ObjectRef resource, String relation,
                                       long maxRevision, Set<ExpandKey> visited) {
        var key = new ExpandKey(resource, relation);
        if (!visited.add(key)) {
            return UsersetTree.leaf(Set.of());
        }

        NamespaceConfig nsConfig = configs.get(resource.namespace());
        if (nsConfig == null) {
            return expandDirect(resource, relation, maxRevision, visited);
        }

        RelationConfig relConfig = nsConfig.getRelation(relation);
        if (relConfig == null) {
            return expandDirect(resource, relation, maxRevision, visited);
        }

        return expandRule(relConfig.rewrite(), resource, relation, maxRevision, visited);
    }

    private UsersetTree expandRule(RewriteRule rule, ObjectRef resource, String relation,
                                   long maxRevision, Set<ExpandKey> visited) {
        return switch (rule) {
            case RewriteRule.This t -> expandDirect(resource, relation, maxRevision, visited);

            case RewriteRule.ComputedUserset cu ->
                expandInternal(resource, cu.relation(), maxRevision, visited);

            case RewriteRule.TupleToUserset ttu -> {
                List<RelationTuple> parents = store.read(resource, ttu.tuplesetRelation(), maxRevision);
                List<UsersetTree> children = new ArrayList<>();
                for (RelationTuple parentTuple : parents) {
                    ObjectRef parent = parentTuple.subject().asObjectRef();
                    children.add(expandInternal(parent, ttu.computedRelation(), maxRevision, visited));
                }
                yield children.isEmpty()
                        ? UsersetTree.leaf(Set.of())
                        : UsersetTree.intermediate("tuple_to_userset", children);
            }

            case RewriteRule.Union u -> {
                List<UsersetTree> children = u.children().stream()
                        .map(child -> expandRule(child, resource, relation, maxRevision, visited))
                        .collect(Collectors.toList());
                yield UsersetTree.intermediate("union", children);
            }

            case RewriteRule.Intersection inter -> {
                List<UsersetTree> children = inter.children().stream()
                        .map(child -> expandRule(child, resource, relation, maxRevision, visited))
                        .collect(Collectors.toList());
                yield UsersetTree.intermediate("intersection", children);
            }

            case RewriteRule.Exclusion ex ->
                UsersetTree.intermediate("exclusion", List.of(
                        expandRule(ex.base(), resource, relation, maxRevision, visited),
                        expandRule(ex.subtract(), resource, relation, maxRevision, visited)
                ));
        };
    }

    private UsersetTree expandDirect(ObjectRef resource, String relation,
                                     long maxRevision, Set<ExpandKey> visited) {
        List<RelationTuple> tuples = store.read(resource, relation, maxRevision);
        Set<SubjectRef> directSubjects = new LinkedHashSet<>();
        List<UsersetTree> usersetChildren = new ArrayList<>();

        for (RelationTuple tuple : tuples) {
            SubjectRef subject = tuple.subject();
            if (subject.isUserset()) {
                usersetChildren.add(expandInternal(
                        subject.asObjectRef(), subject.relation(), maxRevision, visited));
            } else {
                directSubjects.add(subject);
            }
        }

        if (usersetChildren.isEmpty()) {
            return UsersetTree.leaf(directSubjects);
        }

        List<UsersetTree> allChildren = new ArrayList<>();
        if (!directSubjects.isEmpty()) {
            allChildren.add(UsersetTree.leaf(directSubjects));
        }
        allChildren.addAll(usersetChildren);
        return UsersetTree.intermediate("direct+groups", allChildren);
    }

    private record ExpandKey(ObjectRef resource, String relation) {}
}
