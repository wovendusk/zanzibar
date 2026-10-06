package dev.zanzibar.acl.store;

import dev.zanzibar.model.ObjectRef;
import dev.zanzibar.model.RelationTuple;
import dev.zanzibar.model.SubjectRef;
import dev.zanzibar.model.Zookie;
import dev.zanzibar.store.TupleStore;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * TupleStore backed by one append-only PostgreSQL table.
 *
 * A write inserts a row with active = true; a delete inserts a row with
 * active = false (a tombstone). Each row takes the next number from a
 * sequence as its revision. To read "as of revision R", take for each tuple
 * its newest row with revision <= R and keep it if that row is active.
 */
public class PostgreSQLTupleStore implements TupleStore {

    private static final String INSERT_SQL = """
            INSERT INTO tuples (resource_ns, resource_id, relation,
                                subject_ns, subject_id, subject_rel,
                                revision, active)
            VALUES (?, ?, ?, ?, ?, ?, nextval('revision_seq'), ?)
            RETURNING revision
            """;

    private final JdbcTemplate jdbc;

    public PostgreSQLTupleStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Zookie write(ObjectRef resource, String relation, SubjectRef subject) {
        return insert(resource, relation, subject, true);
    }

    @Override
    public Zookie delete(ObjectRef resource, String relation, SubjectRef subject) {
        return insert(resource, relation, subject, false);
    }

    private Zookie insert(ObjectRef resource, String relation, SubjectRef subject, boolean active) {
        Long revision = jdbc.queryForObject(INSERT_SQL, Long.class,
                resource.namespace(), resource.id(), relation,
                subject.namespace(), subject.id(), subject.relation(),
                active);
        return new Zookie(revision);
    }

    @Override
    public List<RelationTuple> read(ObjectRef resource, String relation, long maxRevision) {
        // DISTINCT ON keeps, for each subject, the first row in the ORDER BY,
        // which is that subject's newest row at or below maxRevision.
        String sql = """
                SELECT subject_ns, subject_id, subject_rel
                FROM (
                    SELECT DISTINCT ON (subject_ns, subject_id, COALESCE(subject_rel, ''))
                           subject_ns, subject_id, subject_rel, active
                    FROM tuples
                    WHERE resource_ns = ? AND resource_id = ? AND relation = ?
                      AND revision <= ?
                    ORDER BY subject_ns, subject_id, COALESCE(subject_rel, ''), revision DESC
                ) newest
                WHERE active = true
                """;
        return jdbc.query(sql,
                (rs, rowNum) -> new RelationTuple(
                        resource,
                        relation,
                        new SubjectRef(rs.getString("subject_ns"),
                                rs.getString("subject_id"),
                                rs.getString("subject_rel"))),
                resource.namespace(), resource.id(), relation, maxRevision);
    }

    @Override
    public boolean exists(ObjectRef resource, String relation, SubjectRef subject, long maxRevision) {
        String sql = """
                SELECT active FROM tuples
                WHERE resource_ns = ? AND resource_id = ? AND relation = ?
                  AND subject_ns = ? AND subject_id = ?
                  AND subject_rel IS NOT DISTINCT FROM ?
                  AND revision <= ?
                ORDER BY revision DESC
                LIMIT 1
                """;
        List<Boolean> newest = jdbc.query(sql,
                (rs, rowNum) -> rs.getBoolean("active"),
                resource.namespace(), resource.id(), relation,
                subject.namespace(), subject.id(), subject.relation(),
                maxRevision);
        return !newest.isEmpty() && newest.get(0);
    }

    @Override
    public long latestRevision() {
        Long latest = jdbc.queryForObject("SELECT COALESCE(MAX(revision), 0) FROM tuples", Long.class);
        return latest == null ? 0 : latest;
    }
}
