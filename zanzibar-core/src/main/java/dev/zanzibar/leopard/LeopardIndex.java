package dev.zanzibar.leopard;

import dev.zanzibar.model.ObjectRef;
import dev.zanzibar.model.SubjectRef;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * A Leopard-style index for group membership.
 *
 * Deciding whether a user is in a deeply nested group normally means walking
 * the group graph one tuple read at a time. This index flattens the nesting
 * ahead of time so that the question becomes a lookup in two tables, named as
 * in the Zanzibar paper:
 *
 *   GROUP2GROUP:  group -> itself and every group nested inside it, at any depth
 *   MEMBER2GROUP: user  -> the groups the user is a direct member of
 *
 * A user is in a group if one of the user's direct groups appears in the
 * group's GROUP2GROUP set.
 *
 * The index is not the source of truth. It is fed the stream of tuple changes
 * and so runs slightly behind the tuple store. indexedThrough() says how far
 * it has got; callers use that to decide whether the index is fresh enough.
 *
 * The Kafka listener thread updates the index while HTTP request threads read
 * it, so every public method is synchronized.
 */
public class LeopardIndex {

    private final String groupNamespace;
    private final String membershipRelation;

    /** group -> the groups directly nested in it. */
    private final Map<String, Set<String>> childGroups = new HashMap<>();

    /** GROUP2GROUP. Rebuilt from childGroups whenever nesting changes. */
    private final Map<String, Set<String>> groupToGroups = new HashMap<>();

    /** MEMBER2GROUP. */
    private final Map<String, Set<String>> memberToGroups = new HashMap<>();

    private long indexedThrough = 0;

    public LeopardIndex(String groupNamespace, String membershipRelation) {
        this.groupNamespace = groupNamespace;
        this.membershipRelation = membershipRelation;
    }

    /** The highest revision whose change has been applied to the index. */
    public synchronized long indexedThrough() {
        return indexedThrough;
    }

    // --- Applying the change stream ---

    public synchronized void applyWrite(ObjectRef resource, String relation, SubjectRef subject, long revision) {
        if (isMembershipTuple(resource, relation)) {
            String group = resource.toString();
            if (isNestedGroup(subject)) {
                String child = subject.asObjectRef().toString();
                Set<String> children = childGroups.get(group);
                if (children == null) {
                    children = new HashSet<>();
                    childGroups.put(group, children);
                }
                children.add(child);
                rebuildGroupToGroups();
            } else if (!subject.isUserset()) {
                String member = subject.toString();
                Set<String> groups = memberToGroups.get(member);
                if (groups == null) {
                    groups = new HashSet<>();
                    memberToGroups.put(member, groups);
                }
                groups.add(group);
            }
        }
        indexedThrough = Math.max(indexedThrough, revision);
    }

    public synchronized void applyDelete(ObjectRef resource, String relation, SubjectRef subject, long revision) {
        if (isMembershipTuple(resource, relation)) {
            String group = resource.toString();
            if (isNestedGroup(subject)) {
                Set<String> children = childGroups.get(group);
                if (children != null) {
                    children.remove(subject.asObjectRef().toString());
                    rebuildGroupToGroups();
                }
            } else if (!subject.isUserset()) {
                Set<String> groups = memberToGroups.get(subject.toString());
                if (groups != null) {
                    groups.remove(group);
                }
            }
        }
        indexedThrough = Math.max(indexedThrough, revision);
    }

    // --- Queries ---

    /** Whether member is in group, directly or through nested groups, as far as the index knows. */
    public synchronized boolean isMember(SubjectRef member, ObjectRef group) {
        Set<String> directGroups = memberToGroups.get(member.toString());
        if (directGroups == null) {
            return false;
        }
        String groupKey = group.toString();
        Set<String> reachable = groupToGroups.get(groupKey);
        if (reachable == null) {
            // A group with nothing nested in it contains only itself.
            return directGroups.contains(groupKey);
        }
        for (String direct : directGroups) {
            if (reachable.contains(direct)) {
                return true;
            }
        }
        return false;
    }

    // --- Helpers ---

    private boolean isMembershipTuple(ObjectRef resource, String relation) {
        return resource.namespace().equals(groupNamespace) && relation.equals(membershipRelation);
    }

    /** True for a subject like group:backend#member, i.e. a whole group nested in another. */
    private boolean isNestedGroup(SubjectRef subject) {
        return subject.isUserset()
                && subject.namespace().equals(groupNamespace)
                && membershipRelation.equals(subject.relation());
    }

    /**
     * Recompute GROUP2GROUP from scratch: for every group, find all groups
     * reachable by following nesting edges (breadth-first search). Group
     * nesting changes rarely compared with how often membership is checked,
     * so a full rebuild on each change keeps the code simple.
     */
    private void rebuildGroupToGroups() {
        groupToGroups.clear();
        for (String group : childGroups.keySet()) {
            Set<String> reachable = new HashSet<>();
            ArrayDeque<String> queue = new ArrayDeque<>();
            reachable.add(group);
            queue.add(group);
            while (!queue.isEmpty()) {
                String current = queue.poll();
                Set<String> children = childGroups.get(current);
                if (children == null) {
                    continue;
                }
                for (String child : children) {
                    if (reachable.add(child)) {
                        queue.add(child);
                    }
                }
            }
            groupToGroups.put(group, reachable);
        }
    }
}
