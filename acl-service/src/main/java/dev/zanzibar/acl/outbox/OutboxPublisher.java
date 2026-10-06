package dev.zanzibar.acl.outbox;

import dev.zanzibar.acl.config.EngineConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Sends outbox rows to Kafka.
 *
 * Every half second it reads the unsent rows in id order, sends each one,
 * waits for Kafka to confirm it, and only then marks the row as sent. If
 * sending fails it stops and tries the same row again on the next run, which
 * keeps the events in order.
 *
 * If the service stops between "Kafka confirmed" and "row marked as sent",
 * the row is sent again after restart. So an event is delivered at least
 * once, possibly twice, and consumers must cope with seeing one twice.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private record PendingMessage(long id, String key, String payload) {
    }

    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;

    public OutboxPublisher(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka) {
        this.jdbc = jdbc;
        this.kafka = kafka;
    }

    @Scheduled(fixedDelay = 500)
    public void publishPending() {
        List<PendingMessage> pending = jdbc.query(
                "SELECT id, msg_key, payload FROM outbox WHERE published = false ORDER BY id LIMIT 100",
                (rs, rowNum) -> new PendingMessage(
                        rs.getLong("id"), rs.getString("msg_key"), rs.getString("payload")));

        for (PendingMessage message : pending) {
            try {
                kafka.send(EngineConfig.CHANGES_TOPIC, message.key(), message.payload())
                        .get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("Could not publish outbox row {}, will retry: {}", message.id(), e.toString());
                return;
            }
            jdbc.update("UPDATE outbox SET published = true WHERE id = ?", message.id());
        }
    }
}
