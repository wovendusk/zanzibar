package dev.zanzibar.acl.store;

import dev.zanzibar.model.ObjectRef;
import dev.zanzibar.model.RelationTuple;
import dev.zanzibar.model.SubjectRef;
import dev.zanzibar.model.Zookie;
import dev.zanzibar.store.TupleStore;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * PostgreSQL-backed implementation of {@link TupleStore}.
 *
 * Each write/delete inserts a new row with a revision from a PostgreSQL sequence.
 * Reads use snapshot semantics: find the latest revision ≤ maxRevision for each tuple.
 */
public class PostgreSQLTupleStore implements TupleStore {

    private final JdbcTemplate jdbc;

    public PostgreSQLTupleStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Zookie write(ObjectRef resource, String relation, SubjectRef subject) {
        Long revision = jdbc.queryForObject(
                """
                INSERT INTO tuples (resource_ns, resource_id, relation,
                                    subject_ns, subject_id, subject_rel,
                                    revision, active)
                VALUES (?, ?, ?, ?, ?, ?, nextval('revision_seq'), true)
                RETURNING revision
                """,
                Long.class,
                resource.namespace(), resource.id(), relation,
                subject.namespace(), subject.id(), subject.relation());
        return new Zookie(revision);
    }

    @Override
    public Zookie delete(ObjectRef resource, String relation, SubjectRef subject) {
        Long revision = jdbc.queryForObject(
                """
                INSERT INTO tuples (resource_ns, resource_id, relation,
                                    subject_ns, subject_id, subject_rel,
                                    revision, active)
                VALUES (?, ?, ?, ?, ?, ?, nextval('revision_seq'), false)
                RETURNING revision
                """,
                Long.class,
                resource.namespace(), resource.id(), relation,
                subject.namespace(), subject.id(), subject.relation());
        return new Zookie(revision);
    }

    @Override
    public List<RelationTuple> read(ObjectRef resource, String relation, long maxRevision) {
        return jdbc.query(
                """
                SELECT t.resource_ns, t.resource_id, t.relation,
                       t.subject_ns, t.subject_id, t.subject_rel
                FROM tuples t
                INNER JOIN (
                    SELECT subject_ns, subject_id, COALESCE(subject_rel, '') AS sr,
                           MAX(revision) AS max_rev
                    FROM tuples
                    WHERE resource_ns = ? AND resource_id = ? AND relation = ?
                      AND revision <= ?
                    GROUP BY subject_ns, subject_id, COALESCE(subject_rel, '')
                ) latest ON t.subject_ns = latest.subject_ns
                        AND t.subject_id = latest.subject_id
                        AND COALESCE(t.subject_rel, '') = latest.sr
                        AND t.revision = latest.max_rev
                WHERE t.active = true
                """,
                (rs, rowNum) -> {
                    String subRel = rs.getString("subject_rel");
                    SubjectRef subject = subRel != null
                            ? SubjectRef.userset(rs.getString("subject_ns"), rs.getString("subject_id"), subRel)
                            : SubjectRef.user(rs.getString("subject_ns"), rs.getString("subject_id"));
                    return new RelationTuple(
                            new ObjectRef(rs.getString("resource_ns"), rs.getString("resource_id")),
                            rs.getString("relation"),
                            subject);
                },
                resource.namespace(), resource.id(), relation, maxRevision);
    }

    @Override
    public boolean exists(ObjectRef resource, String relation, SubjectRef subject, long maxRevision) {
        List<Boolean> results = jdbc.query(
                """
                SELECT active FROM tuples
                WHERE resource_ns = ? AND resource_id = ? AND relation = ?
                  AND subject_ns = ? AND subject_id = ?
                  AND subject_rel IS NOT DISTINCT FROM ?
                  AND revision <= ?
                ORDER BY revision DESC
                LIMIT 1
                """,
                (rs, rowNum) -> rs.getBoolean("active"),
                resource.namespace(), resource.id(), relation,
                subject.namespace(), subject.id(), subject.relation(),
                maxRevision);
        return !results.isEmpty() && results.getFirst();
    }

    @Override
    public long latestRevision() {
        Long val = jdbc.queryForObject("SELECT COALESCE(MAX(revision), 0) FROM tuples", Long.class);
        return val != null ? val : 0;
    }

    @Override
    public long safeRevision() {
        return latestRevision();
    }
}
