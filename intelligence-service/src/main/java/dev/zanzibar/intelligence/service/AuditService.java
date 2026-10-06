package dev.zanzibar.intelligence.service;

import dev.zanzibar.events.PermissionChangeEvent;
import dev.zanzibar.intelligence.dto.AuditEntry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/** Stores every permission change and answers questions about the history. */
@Service
public class AuditService {

    private static final String SELECT_COLUMNS = """
            SELECT type, resource_ns, resource_id, relation,
                   subject_ns, subject_id, subject_rel,
                   revision, event_timestamp
            FROM audit_log
            """;

    private final JdbcTemplate jdbc;

    public AuditService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Saves one change. Kafka may deliver the same event more than once; the
     * revision is unique, so a repeat is ignored by ON CONFLICT DO NOTHING.
     */
    public void record(PermissionChangeEvent event) {
        jdbc.update("""
                INSERT INTO audit_log (type, resource_ns, resource_id, relation,
                                       subject_ns, subject_id, subject_rel,
                                       revision, event_timestamp)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (revision) DO NOTHING
                """,
                event.type(), event.resourceNs(), event.resourceId(), event.relation(),
                event.subjectNs(), event.subjectId(), event.subjectRel(),
                event.revision(), Timestamp.from(Instant.ofEpochMilli(event.timestamp())));
    }

    public List<AuditEntry> findByResource(String resourceNs, String resourceId, int limit) {
        return jdbc.query(
                SELECT_COLUMNS + "WHERE resource_ns = ? AND resource_id = ? ORDER BY revision DESC LIMIT ?",
                (rs, rowNum) -> toEntry(rs),
                resourceNs, resourceId, limit);
    }

    public List<AuditEntry> findBySubject(String subjectNs, String subjectId, int limit) {
        return jdbc.query(
                SELECT_COLUMNS + "WHERE subject_ns = ? AND subject_id = ? ORDER BY revision DESC LIMIT ?",
                (rs, rowNum) -> toEntry(rs),
                subjectNs, subjectId, limit);
    }

    private AuditEntry toEntry(ResultSet rs) throws SQLException {
        return new AuditEntry(
                rs.getString("type"),
                rs.getString("resource_ns"), rs.getString("resource_id"),
                rs.getString("relation"),
                rs.getString("subject_ns"), rs.getString("subject_id"), rs.getString("subject_rel"),
                rs.getLong("revision"),
                rs.getTimestamp("event_timestamp").toInstant().toString());
    }
}
