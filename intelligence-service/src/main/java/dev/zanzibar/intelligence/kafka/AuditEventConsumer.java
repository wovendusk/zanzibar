package dev.zanzibar.intelligence.kafka;

import dev.zanzibar.events.PermissionChangeEvent;
import dev.zanzibar.intelligence.service.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes permission change events and stores them as audit log entries.
 */
@Component
public class AuditEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(AuditEventConsumer.class);

    private final AuditService auditService;

    public AuditEventConsumer(AuditService auditService) {
        this.auditService = auditService;
    }

    @KafkaListener(topics = "permissions.changes", groupId = "intelligence-audit")
    public void handleChange(PermissionChangeEvent event) {
        auditService.record(event);
        log.debug("Recorded audit entry: {} {}#{} rev={}",
                event.type(), event.resourceNs() + ":" + event.resourceId(),
                event.relation(), event.revision());
    }
}
