package dev.zanzibar.store;

import dev.zanzibar.model.ObjectRef;
import dev.zanzibar.model.RelationTuple;
import dev.zanzibar.model.SubjectRef;
import dev.zanzibar.model.Zookie;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A TupleStore kept in memory, used by the unit tests.
 *
 * It is an append-only log, the same shape as the PostgreSQL table: every
 * write and delete adds one entry with the next revision number. Reads replay
 * the log up to the requested revision. That is slow for a large log but
 * simple, which is the point of this class.
 */
public class InMemoryTupleStore implements TupleStore {

    private record Entry(long revision, RelationTuple tuple, boolean active) {
    }

    private final List<Entry> log = new ArrayList<>();
    private long revision = 0;

    @Override
    public synchronized Zookie write(ObjectRef resource, String relation, SubjectRef subject) {
        return append(new RelationTuple(resource, relation, subject), true);
    }

    @Override
    public synchronized Zookie delete(ObjectRef resource, String relation, SubjectRef subject) {
        return append(new RelationTuple(resource, relation, subject), false);
    }

    private Zookie append(RelationTuple tuple, boolean active) {
        revision++;
        log.add(new Entry(revision, tuple, active));
        return new Zookie(revision);
    }

    @Override
    public synchronized List<RelationTuple> read(ObjectRef resource, String relation, long maxRevision) {
        // Replay the log in order; the last entry seen for a tuple decides whether it is live.
        Map<RelationTuple, Boolean> state = new LinkedHashMap<>();
        for (Entry entry : log) {
            if (entry.revision() > maxRevision) {
                break;
            }
            RelationTuple tuple = entry.tuple();
            if (tuple.resource().equals(resource) && tuple.relation().equals(relation)) {
                state.put(tuple, entry.active());
            }
        }
        List<RelationTuple> live = new ArrayList<>();
        for (Map.Entry<RelationTuple, Boolean> e : state.entrySet()) {
            if (e.getValue()) {
                live.add(e.getKey());
            }
        }
        return live;
    }

    @Override
    public synchronized boolean exists(ObjectRef resource, String relation, SubjectRef subject, long maxRevision) {
        RelationTuple wanted = new RelationTuple(resource, relation, subject);
        boolean active = false;
        for (Entry entry : log) {
            if (entry.revision() > maxRevision) {
                break;
            }
            if (entry.tuple().equals(wanted)) {
                active = entry.active();
            }
        }
        return active;
    }

    @Override
    public synchronized long latestRevision() {
        return revision;
    }
}
