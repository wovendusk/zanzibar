package dev.zanzibar.events;

/**
 * Kafka event published on every tuple write or delete.
 * Consumed by the Leopard service (index updates) and
 * the Intelligence service (audit log).
 */
public record PermissionChangeEvent(
        String type,            // "WRITE" or "DELETE"
        String resourceNs,
        String resourceId,
        String relation,
        String subjectNs,
        String subjectId,
        String subjectRel,      // nullable — null for direct users
        long revision,
        long timestamp
) {
    public static PermissionChangeEvent write(String resourceNs, String resourceId,
                                              String relation, String subjectNs,
                                              String subjectId, String subjectRel,
                                              long revision) {
        return new PermissionChangeEvent("WRITE", resourceNs, resourceId, relation,
                subjectNs, subjectId, subjectRel, revision, System.currentTimeMillis());
    }

    public static PermissionChangeEvent delete(String resourceNs, String resourceId,
                                               String relation, String subjectNs,
                                               String subjectId, String subjectRel,
                                               long revision) {
        return new PermissionChangeEvent("DELETE", resourceNs, resourceId, relation,
                subjectNs, subjectId, subjectRel, revision, System.currentTimeMillis());
    }
}
