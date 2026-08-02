package dev.zanzibar.store;

import dev.zanzibar.model.ObjectRef;
import dev.zanzibar.model.RelationTuple;
import dev.zanzibar.model.SubjectRef;
import dev.zanzibar.model.Zookie;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;

public class InMemoryTupleStore implements TupleStore {

    private final AtomicLong revisionCounter = new AtomicLong(0);

    /**
     * Highest revision considered durable/replicated. In synchronous mode it tracks
     * {@link #revisionCounter}; when replication is paused it lags behind, modelling
     * a replica that hasn't yet caught up. This is what lets bounded-staleness reads
     * observe the "safe vs latest" gap that exists in a real distributed store.
     */
    private final AtomicLong safeRevision = new AtomicLong(0);

    private volatile boolean replicationSynchronous = true;

    /**
     * Primary storage: full tuple key → version chain.
     * The version chain maps revision → active (true = write, false = tombstone).
     * ConcurrentSkipListMap keeps revisions sorted for efficient floorEntry().
     */
    private final ConcurrentHashMap<TupleKey, ConcurrentSkipListMap<Long, Boolean>> tuples =
            new ConcurrentHashMap<>();

    /**
     * Index: (resource, relation) → set of tuple keys with that prefix.
     * Enables efficient "give me all subjects for this object#relation" queries.
     */
    private final ConcurrentHashMap<ObjectRelKey, Set<TupleKey>> objectRelIndex =
            new ConcurrentHashMap<>();

    @Override
    public Zookie write(ObjectRef resource, String relation, SubjectRef subject) {
        long revision = revisionCounter.incrementAndGet();
        var key = new TupleKey(resource, relation, subject);

        tuples.computeIfAbsent(key, k -> new ConcurrentSkipListMap<>())
                .put(revision, true);

        objectRelIndex
                .computeIfAbsent(new ObjectRelKey(resource, relation), k -> ConcurrentHashMap.newKeySet())
                .add(key);

        markReplicated(revision);
        return new Zookie(revision);
    }

    @Override
    public Zookie delete(ObjectRef resource, String relation, SubjectRef subject) {
        long revision = revisionCounter.incrementAndGet();
        var key = new TupleKey(resource, relation, subject);

        tuples.computeIfAbsent(key, k -> new ConcurrentSkipListMap<>())
                .put(revision, false);

        markReplicated(revision);
        return new Zookie(revision);
    }

    /** Advance the safe revision if replication is synchronous (the default). */
    private void markReplicated(long revision) {
        if (replicationSynchronous) {
            safeRevision.accumulateAndGet(revision, Math::max);
        }
    }

    @Override
    public List<RelationTuple> read(ObjectRef resource, String relation, long maxRevision) {
        var orlKey = new ObjectRelKey(resource, relation);
        Set<TupleKey> keys = objectRelIndex.get(orlKey);
        if (keys == null) {
            return List.of();
        }

        List<RelationTuple> result = new ArrayList<>();
        for (TupleKey key : keys) {
            if (isActiveAt(key, maxRevision)) {
                result.add(new RelationTuple(key.resource(), key.relation(), key.subject()));
            }
        }
        return result;
    }

    @Override
    public boolean exists(ObjectRef resource, String relation, SubjectRef subject, long maxRevision) {
        return isActiveAt(new TupleKey(resource, relation, subject), maxRevision);
    }

    @Override
    public long latestRevision() {
        return revisionCounter.get();
    }

    @Override
    public long safeRevision() {
        return safeRevision.get();
    }

    // --- Replication-lag simulation (for bounded-staleness demonstrations) ---

    /**
     * Stop advancing the safe revision. New writes still commit (latestRevision
     * moves) but are not yet "replicated", so {@link #safeRevision()} lags behind —
     * exactly the window in which a stale replica read differs from a leader read.
     */
    public void pauseReplication() {
        replicationSynchronous = false;
    }

    /** Resume synchronous replication and catch the safe revision up to latest. */
    public void resumeReplication() {
        replicationSynchronous = true;
        safeRevision.accumulateAndGet(revisionCounter.get(), Math::max);
    }

    /** Manually advance the safe revision (capped at latest) to model partial catch-up. */
    public void advanceSafeRevisionTo(long revision) {
        long capped = Math.min(revision, revisionCounter.get());
        safeRevision.accumulateAndGet(capped, Math::max);
    }

    private boolean isActiveAt(TupleKey key, long maxRevision) {
        ConcurrentSkipListMap<Long, Boolean> chain = tuples.get(key);
        if (chain == null) {
            return false;
        }
        Map.Entry<Long, Boolean> entry = chain.floorEntry(maxRevision);
        return entry != null && entry.getValue();
    }

    record TupleKey(ObjectRef resource, String relation, SubjectRef subject) {}

    record ObjectRelKey(ObjectRef resource, String relation) {}
}
