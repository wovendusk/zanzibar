package dev.zanzibar.acl.config;

import dev.zanzibar.ZanzibarEngine;
import dev.zanzibar.acl.namespace.NamespaceStore;
import dev.zanzibar.acl.store.PostgreSQLTupleStore;
import dev.zanzibar.config.NamespaceConfig;
import dev.zanzibar.config.NamespaceRegistry;
import dev.zanzibar.config.RewriteRule;
import dev.zanzibar.store.TupleStore;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Creates the objects the ACL service is built from.
 */
@Configuration
public class EngineConfig {

    public static final String CHANGES_TOPIC = "permissions.changes";

    @Bean
    public TupleStore tupleStore(JdbcTemplate jdbc) {
        return new PostgreSQLTupleStore(jdbc);
    }

    @Bean
    public ZanzibarEngine zanzibarEngine(TupleStore tupleStore,
                                         NamespaceStore namespaceStore,
                                         @Value("${zanzibar.quantum:1}") long quantum) {
        NamespaceRegistry registry = new NamespaceRegistry();

        // Built-in configs, so the service is usable on an empty database.
        registry.register(NamespaceConfig.builder("group")
                .relation("member", RewriteRule.thisRelation())
                .build());
        registry.register(NamespaceConfig.builder("folder")
                .relation("parent", RewriteRule.thisRelation())
                .relation("viewer", RewriteRule.union(
                        RewriteRule.thisRelation(),
                        RewriteRule.tupleToUserset("parent", "viewer")))
                .build());
        registry.register(NamespaceConfig.builder("doc")
                .relation("parent", RewriteRule.thisRelation())
                .relation("owner", RewriteRule.thisRelation())
                .relation("editor", RewriteRule.union(
                        RewriteRule.thisRelation(),
                        RewriteRule.computedUserset("owner")))
                .relation("viewer", RewriteRule.union(
                        RewriteRule.thisRelation(),
                        RewriteRule.computedUserset("editor"),
                        RewriteRule.tupleToUserset("parent", "viewer")))
                .build());

        // Configs saved through the API replace or add to the built-in ones.
        for (NamespaceConfig saved : namespaceStore.loadAll()) {
            registry.register(saved);
        }

        return new ZanzibarEngine(tupleStore, registry, quantum);
    }

    /**
     * Declares the topic so it is created at startup if missing. One partition
     * means Kafka keeps every change in a single ordered log, so consumers see
     * changes in revision order.
     */
    @Bean
    public NewTopic changesTopic() {
        return TopicBuilder.name(CHANGES_TOPIC).partitions(1).replicas(1).build();
    }
}
