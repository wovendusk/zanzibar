package dev.zanzibar.acl.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.zanzibar.events.PermissionChangeEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Stores events that still have to be sent to Kafka.
 *
 * add() is called inside the transaction that writes the tuple, so the event
 * row is committed together with the tuple row.
 */
@Component
public class Outbox {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public Outbox(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void add(PermissionChangeEvent event) {
        String key = event.resourceNs() + ":" + event.resourceId() + "#" + event.relation();
        String payload;
        try {
            payload = json.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not turn event into JSON", e);
        }
        jdbc.update("INSERT INTO outbox (msg_key, payload) VALUES (?, ?)", key, payload);
    }
}
