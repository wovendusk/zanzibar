package dev.zanzibar.leopard.service.kafka;

import dev.zanzibar.events.PermissionChangeEvent;
import dev.zanzibar.leopard.LeopardIndex;
import dev.zanzibar.model.ObjectRef;
import dev.zanzibar.model.SubjectRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes permission change events from Kafka and feeds them
 * into the in-memory Leopard index.
 */
@Component
public class PermissionChangeConsumer {

    private static final Logger log = LoggerFactory.getLogger(PermissionChangeConsumer.class);

    private final LeopardIndex index;

    public PermissionChangeConsumer(LeopardIndex index) {
        this.index = index;
    }

    @KafkaListener(topics = "permissions.changes", groupId = "leopard-indexer")
    public void handleChange(PermissionChangeEvent event) {
        ObjectRef resource = new ObjectRef(event.resourceNs(), event.resourceId());
        SubjectRef subject = event.subjectRel() != null
                ? SubjectRef.userset(event.subjectNs(), event.subjectId(), event.subjectRel())
                : SubjectRef.user(event.subjectNs(), event.subjectId());

        switch (event.type()) {
            case "WRITE" -> {
                index.applyWrite(resource, event.relation(), subject, event.revision());
                log.debug("Indexed WRITE: {}#{} rev={}", resource, event.relation(), event.revision());
            }
            case "DELETE" -> {
                index.applyDelete(resource, event.relation(), subject, event.revision());
                log.debug("Indexed DELETE: {}#{} rev={}", resource, event.relation(), event.revision());
            }
            default -> log.warn("Unknown event type: {}", event.type());
        }
    }
}
