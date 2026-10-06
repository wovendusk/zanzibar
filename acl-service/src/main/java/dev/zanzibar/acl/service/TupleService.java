package dev.zanzibar.acl.service;

import dev.zanzibar.ZanzibarEngine;
import dev.zanzibar.acl.outbox.Outbox;
import dev.zanzibar.events.PermissionChangeEvent;
import dev.zanzibar.model.ObjectRef;
import dev.zanzibar.model.SubjectRef;
import dev.zanzibar.model.Zookie;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies tuple writes and deletes.
 *
 * Each change does two things: append the tuple row, and record an event for
 * the other services. Both happen in one database transaction, so either both
 * are saved or neither is. The event goes into the outbox table, not straight
 * to Kafka; a separate publisher sends it afterwards (see OutboxPublisher).
 */
@Service
public class TupleService {

    private final ZanzibarEngine engine;
    private final Outbox outbox;
    private final JdbcTemplate jdbc;

    public TupleService(ZanzibarEngine engine, Outbox outbox, JdbcTemplate jdbc) {
        this.engine = engine;
        this.outbox = outbox;
        this.jdbc = jdbc;
    }

    @Transactional
    public Zookie write(ObjectRef resource, String relation, SubjectRef subject) {
        lockForWriting();
        Zookie zookie = engine.write(resource, relation, subject);
        outbox.add(event(PermissionChangeEvent.WRITE, resource, relation, subject, zookie));
        return zookie;
    }

    @Transactional
    public Zookie delete(ObjectRef resource, String relation, SubjectRef subject) {
        lockForWriting();
        Zookie zookie = engine.delete(resource, relation, subject);
        outbox.add(event(PermissionChangeEvent.DELETE, resource, relation, subject, zookie));
        return zookie;
    }

    /**
     * Lets only one write transaction run at a time; readers are not blocked.
     * The lock is released when the transaction ends. Without it, two
     * concurrent writes could commit in the opposite order to their revision
     * numbers, and a reader at revision N could miss revision N-1.
     */
    private void lockForWriting() {
        jdbc.execute("LOCK TABLE tuples IN EXCLUSIVE MODE");
    }

    private PermissionChangeEvent event(String type, ObjectRef resource, String relation,
                                        SubjectRef subject, Zookie zookie) {
        return new PermissionChangeEvent(type,
                resource.namespace(), resource.id(), relation,
                subject.namespace(), subject.id(), subject.relation(),
                zookie.revision(), System.currentTimeMillis());
    }
}
