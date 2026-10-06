package dev.zanzibar.intelligence.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.zanzibar.events.PermissionChangeEvent;
import dev.zanzibar.intelligence.service.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Reads tuple changes from Kafka and writes them to the audit log.
 *
 * This service keeps a fixed consumer group, so Kafka remembers how far it
 * has read and after a restart it continues from there.
 */
@Component
public class AuditEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(AuditEventConsumer.class);

    private final AuditService audit;
    private final ObjectMapper json;

    public AuditEventConsumer(AuditService audit, ObjectMapper json) {
        this.audit = audit;
        this.json = json;
    }

    @KafkaListener(topics = "permissions.changes")
    public void onChange(String message) {
        PermissionChangeEvent event;
        try {
            event = json.readValue(message, PermissionChangeEvent.class);
        } catch (JsonProcessingException e) {
            log.warn("Skipping a message that is not a valid event: {}", message);
            return;
        }
        audit.record(event);
    }
}
