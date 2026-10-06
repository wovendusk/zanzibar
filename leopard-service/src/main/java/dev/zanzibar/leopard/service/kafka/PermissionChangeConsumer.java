package dev.zanzibar.leopard.service.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.zanzibar.events.PermissionChangeEvent;
import dev.zanzibar.leopard.LeopardIndex;
import dev.zanzibar.model.ObjectRef;
import dev.zanzibar.model.SubjectRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Reads tuple changes from Kafka and applies them to the index.
 *
 * The index only lives in memory, so this service uses a new consumer group
 * each time it starts (see application.yml) and reads the topic from the
 * beginning to rebuild it.
 */
@Component
public class PermissionChangeConsumer {

    private static final Logger log = LoggerFactory.getLogger(PermissionChangeConsumer.class);

    private final LeopardIndex index;
    private final ObjectMapper json;

    public PermissionChangeConsumer(LeopardIndex index, ObjectMapper json) {
        this.index = index;
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

        ObjectRef resource = new ObjectRef(event.resourceNs(), event.resourceId());
        SubjectRef subject = new SubjectRef(event.subjectNs(), event.subjectId(), event.subjectRel());

        // Applying the same event twice leaves the index unchanged, so a
        // message that Kafka delivers again does no harm.
        if (PermissionChangeEvent.WRITE.equals(event.type())) {
            index.applyWrite(resource, event.relation(), subject, event.revision());
        } else if (PermissionChangeEvent.DELETE.equals(event.type())) {
            index.applyDelete(resource, event.relation(), subject, event.revision());
        } else {
            log.warn("Unknown event type: {}", event.type());
        }
    }
}
