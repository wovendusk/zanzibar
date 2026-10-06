package dev.zanzibar.acl.namespace;

import dev.zanzibar.config.NamespaceConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps namespace configs in PostgreSQL as JSON text, so configs added
 * through the API are still there after a restart.
 */
@Component
public class NamespaceStore {

    private static final Logger log = LoggerFactory.getLogger(NamespaceStore.class);

    private final JdbcTemplate jdbc;
    private final NamespaceJson namespaceJson;

    public NamespaceStore(JdbcTemplate jdbc, NamespaceJson namespaceJson) {
        this.jdbc = jdbc;
        this.namespaceJson = namespaceJson;
    }

    public void save(String name, String configJson) {
        jdbc.update("""
                INSERT INTO namespace_configs (name, config_json) VALUES (?, ?)
                ON CONFLICT (name) DO UPDATE SET config_json = EXCLUDED.config_json
                """, name, configJson);
    }

    public List<NamespaceConfig> loadAll() {
        List<String> stored = jdbc.queryForList("SELECT config_json FROM namespace_configs", String.class);
        List<NamespaceConfig> configs = new ArrayList<>();
        for (String configJson : stored) {
            try {
                configs.add(namespaceJson.parseNamespace(configJson));
            } catch (IllegalArgumentException e) {
                // A config saved by an older version may no longer pass validation.
                // Skip it so that one bad row cannot stop the service from starting.
                log.warn("Ignoring stored namespace config that is no longer valid: {}", e.getMessage());
            }
        }
        return configs;
    }
}
