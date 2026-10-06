package dev.zanzibar.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The namespace configs currently in force, by namespace name.
 * Configs can be replaced while the service runs, so access is synchronized.
 */
public class NamespaceRegistry {

    private final Map<String, NamespaceConfig> configs = new HashMap<>();

    public synchronized void register(NamespaceConfig config) {
        configs.put(config.name(), config);
    }

    /** The config of a namespace, or null if none is registered. */
    public synchronized NamespaceConfig get(String namespace) {
        return configs.get(namespace);
    }

    public synchronized List<NamespaceConfig> all() {
        return new ArrayList<>(configs.values());
    }
}
