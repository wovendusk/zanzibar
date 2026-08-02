package dev.zanzibar.leopard;

import dev.zanzibar.model.ObjectRef;
import dev.zanzibar.model.SubjectRef;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * A Leopard-style membership index: an offline, denormalized view that flattens
 * deeply nested group membership so a check becomes a set intersection instead of
 * a recursive walk of the group graph.
 *
 * <h2>What it stores</h2>
 * <ul>
 *   <li>A {@link GroupGraph} of group-contains-group edges, with an incrementally
 *       maintained transitive closure per group.</li>
 *   <li>{@code MEMBER2GROUP}: for each concrete member (a user), the set of groups
 *       it is a <em>direct</em> member of.</li>
 * </ul>
 * Transitivity lives entirely on the group side (the closure); the member side is
 * flat. A check joins the two: {@code member ∈ group} iff the member's direct
 * groups intersect the group's closure.
 *
 * <h2>How it is fed</h2>
 * The index consumes the tuple change-feed via {@link #applyWrite}/{@link #applyDelete}
 * — exactly the model the Zanzibar paper describes (an offline system tailing the
 * write log), rather than scanning storage. It therefore lags the authoritative
 * store slightly; {@link #indexedThrough()} reports how fresh it is, which callers
 * use to decide whether the index may answer a given consistency requirement.
 *
 * <h2>Ids</h2>
 * Groups are interned to dense {@code long} ids so closures and member-sets can be
 * represented as {@link SortedIdSet}s and intersected by galloping. Materialized
 * snapshots are cached and lazily rebuilt only when their underlying set changed.
 */
public final class LeopardIndex {

    private final String groupNamespace;
    private final String membershipRelation;

    private final GroupGraph graph = new GroupGraph();

    private final Map<String, Long> groupIds = new HashMap<>();
    private long nextGroupId = 0;

    /** MEMBER2GROUP: member key ("user:alice") -> direct group ids. */
    private final Map<String, Set<Long>> directGroupsOfMember = new HashMap<>();

    // Materialized query snapshots, rebuilt lazily when their source set is dirty.
    private final Map<Long, SortedIdSet> closureSnapshot = new HashMap<>();
    private final Set<Long> dirtyClosures = new HashSet<>();
    private final Map<String, SortedIdSet> memberSnapshot = new HashMap<>();
    private final Set<String> dirtyMembers = new HashSet<>();

    private long indexedThrough = 0;

    public LeopardIndex() {
        this("group", "member");
    }

    public LeopardIndex(String groupNamespace, String membershipRelation) {
        this.groupNamespace = groupNamespace;
        this.membershipRelation = membershipRelation;
    }

    /** The highest revision whose change this index has consumed. */
    public long indexedThrough() {
        return indexedThrough;
    }

    /** True if this tuple is a group-membership edge the index tracks. */
    public boolean handles(ObjectRef resource, String relation) {
        return resource.namespace().equals(groupNamespace) && relation.equals(membershipRelation);
    }

    // --- Change-feed ingestion ---

    public void applyWrite(ObjectRef resource, String relation, SubjectRef subject, long revision) {
        if (handles(resource, relation)) {
            long group = internGroup(resource);
            if (isSubgroup(subject)) {
                long child = internGroup(subject.asObjectRef());
                dirtyClosures.addAll(graph.addEdge(group, child));
            } else {
                String key = memberKey(subject);
                directGroupsOfMember.computeIfAbsent(key, k -> new HashSet<>()).add(group);
                dirtyMembers.add(key);
            }
        }
        indexedThrough = Math.max(indexedThrough, revision);
    }

    public void applyDelete(ObjectRef resource, String relation, SubjectRef subject, long revision) {
        if (handles(resource, relation)) {
            Long group = groupIds.get(groupKey(resource));
            if (group != null) {
                if (isSubgroup(subject)) {
                    Long child = groupIds.get(groupKey(subject.asObjectRef()));
                    if (child != null) {
                        dirtyClosures.addAll(graph.removeEdge(group, child));
                    }
                } else {
                    String key = memberKey(subject);
                    Set<Long> groups = directGroupsOfMember.get(key);
                    if (groups != null && groups.remove(group)) {
                        dirtyMembers.add(key);
                    }
                }
            }
        }
        indexedThrough = Math.max(indexedThrough, revision);
    }

    // --- Queries ---

    /** Is {@code member} a (transitive) member of {@code group}, per the current index? */
    public boolean isMember(SubjectRef member, ObjectRef group) {
        Long g = groupIds.get(groupKey(group));
        if (g == null) {
            return false; // no such group has ever been indexed
        }
        SortedIdSet memberGroups = memberSnapshotOf(memberKey(member));
        if (memberGroups.isEmpty()) {
            return false;
        }
        return memberGroups.intersects(closureSnapshotOf(g));
    }

    /** Is {@code sub} a (transitive) subgroup of {@code group}? */
    public boolean isSubgroupOf(ObjectRef sub, ObjectRef group) {
        Long g = groupIds.get(groupKey(group));
        Long s = groupIds.get(groupKey(sub));
        if (g == null || s == null) {
            return false;
        }
        return closureSnapshotOf(g).contains(s);
    }

    /** Size of a group's flattened closure (incl itself) — useful for benchmarks/inspection. */
    public int closureSize(ObjectRef group) {
        Long g = groupIds.get(groupKey(group));
        return g == null ? 0 : closureSnapshotOf(g).size();
    }

    // --- Snapshot materialization (lazy, dirty-tracked) ---

    private SortedIdSet closureSnapshotOf(long g) {
        if (dirtyClosures.remove(g) || !closureSnapshot.containsKey(g)) {
            closureSnapshot.put(g, SortedIdSet.of(graph.closureOf(g)));
        }
        return closureSnapshot.get(g);
    }

    private SortedIdSet memberSnapshotOf(String memberKey) {
        if (dirtyMembers.remove(memberKey) || !memberSnapshot.containsKey(memberKey)) {
            Set<Long> groups = directGroupsOfMember.get(memberKey);
            memberSnapshot.put(memberKey, groups == null ? SortedIdSet.EMPTY : SortedIdSet.of(groups));
        }
        return memberSnapshot.get(memberKey);
    }

    // --- Helpers ---

    private boolean isSubgroup(SubjectRef subject) {
        return subject.isUserset()
                && subject.namespace().equals(groupNamespace)
                && membershipRelation.equals(subject.relation());
    }

    private long internGroup(ObjectRef group) {
        return groupIds.computeIfAbsent(groupKey(group), k -> nextGroupId++);
    }

    private static String groupKey(ObjectRef group) {
        return group.namespace() + ":" + group.id();
    }

    private static String memberKey(SubjectRef subject) {
        return subject.toString();
    }
}
