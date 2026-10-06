package dev.zanzibar.events;

/**
 * The message published to Kafka for every tuple write or delete.
 * It travels as JSON. The Leopard service uses it to update its index and the
 * intelligence service stores it in the audit log.
 */
public record PermissionChangeEvent(
        String type,            // "WRITE" or "DELETE"
        String resourceNs,
        String resourceId,
        String relation,
        String subjectNs,
        String subjectId,
        String subjectRel,      // null for a plain user
        long revision,
        long timestamp
) {
    public static final String WRITE = "WRITE";
    public static final String DELETE = "DELETE";
}
