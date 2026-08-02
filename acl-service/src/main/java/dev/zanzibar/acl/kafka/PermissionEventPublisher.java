package dev.zanzibar.acl.kafka;

import dev.zanzibar.events.PermissionChangeEvent;
import dev.zanzibar.model.ObjectRef;
import dev.zanzibar.model.SubjectRef;
import dev.zanzibar.model.Zookie;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes permission change events to Kafka after each write/delete.
 */
@Component
public class PermissionEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(PermissionEventPublisher.class);
    private static final String TOPIC = "permissions.changes";

    private final KafkaTemplate<String, PermissionChangeEvent> kafka;

    public PermissionEventPublisher(KafkaTemplate<String, PermissionChangeEvent> kafka) {
        this.kafka = kafka;
    }

    public void publishWrite(ObjectRef resource, String relation, SubjectRef subject, Zookie zookie) {
        var event = PermissionChangeEvent.write(
                resource.namespace(), resource.id(), relation,
                subject.namespace(), subject.id(), subject.relation(),
                zookie.revision());
        String key = resource.namespace() + ":" + resource.id() + "#" + relation;
        kafka.send(TOPIC, key, event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish WRITE event for revision {}", zookie.revision(), ex);
                    }
                });
    }

    public void publishDelete(ObjectRef resource, String relation, SubjectRef subject, Zookie zookie) {
        var event = PermissionChangeEvent.delete(
                resource.namespace(), resource.id(), relation,
                subject.namespace(), subject.id(), subject.relation(),
                zookie.revision());
        String key = resource.namespace() + ":" + resource.id() + "#" + relation;
        kafka.send(TOPIC, key, event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish DELETE event for revision {}", zookie.revision(), ex);
                    }
                });
    }
}
