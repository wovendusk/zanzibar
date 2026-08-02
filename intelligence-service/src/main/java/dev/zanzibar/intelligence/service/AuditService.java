package dev.zanzibar.intelligence.service;

import dev.zanzibar.events.PermissionChangeEvent;
import dev.zanzibar.intelligence.dto.AuditEntry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Stores and queries the permission change audit log.
 */
@Service
public class AuditService {

    private final JdbcTemplate jdbc;

    public AuditService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(PermissionChangeEvent event) {
        jdbc.update(
                """
                INSERT INTO audit_log (type, resource_ns, resource_id, relation,
                                       subject_ns, subject_id, subject_rel,
                                       revision, event_timestamp)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                event.type(), event.resourceNs(), event.resourceId(), event.relation(),
                event.subjectNs(), event.subjectId(), event.subjectRel(),
                event.revision(), Instant.ofEpochMilli(event.timestamp()));
    }

    public List<AuditEntry> findByResource(String resourceNs, String resourceId, int limit) {
        return jdbc.query(
                """
                SELECT type, resource_ns, resource_id, relation,
                       subject_ns, subject_id, subject_rel,
                       revision, event_timestamp
                FROM audit_log
                WHERE resource_ns = ? AND resource_id = ?
                ORDER BY revision DESC
                LIMIT ?
                """,
                (rs, rowNum) -> new AuditEntry(
                        rs.getString("type"),
                        rs.getString("resource_ns"), rs.getString("resource_id"),
                        rs.getString("relation"),
                        rs.getString("subject_ns"), rs.getString("subject_id"),
                        rs.getString("subject_rel"),
                        rs.getLong("revision"),
                        rs.getTimestamp("event_timestamp").toInstant().toString()),
                resourceNs, resourceId, limit);
    }

    public List<AuditEntry> findBySubject(String subjectNs, String subjectId, int limit) {
        return jdbc.query(
                """
                SELECT type, resource_ns, resource_id, relation,
                       subject_ns, subject_id, subject_rel,
                       revision, event_timestamp
                FROM audit_log
                WHERE subject_ns = ? AND subject_id = ?
                ORDER BY revision DESC
                LIMIT ?
                """,
                (rs, rowNum) -> new AuditEntry(
                        rs.getString("type"),
                        rs.getString("resource_ns"), rs.getString("resource_id"),
                        rs.getString("relation"),
                        rs.getString("subject_ns"), rs.getString("subject_id"),
                        rs.getString("subject_rel"),
                        rs.getLong("revision"),
                        rs.getTimestamp("event_timestamp").toInstant().toString()),
                subjectNs, subjectId, limit);
    }
}
