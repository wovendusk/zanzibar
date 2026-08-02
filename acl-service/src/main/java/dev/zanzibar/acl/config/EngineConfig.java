package dev.zanzibar.acl.config;

import dev.zanzibar.ZanzibarEngine;
import dev.zanzibar.acl.store.PostgreSQLTupleStore;
import dev.zanzibar.config.NamespaceConfig;
import dev.zanzibar.config.RewriteRule;
import dev.zanzibar.store.TupleStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Wires the Zanzibar engine with PostgreSQL-backed tuple storage.
 *
 * Namespace configs are defined here. In a production system these would
 * be loaded from a config file or database; for this project they're
 * hardcoded to match our test scenarios.
 */
@Configuration
public class EngineConfig {

    @Bean
    public TupleStore tupleStore(JdbcTemplate jdbcTemplate) {
        return new PostgreSQLTupleStore(jdbcTemplate);
    }

    @Bean
    public ZanzibarEngine zanzibarEngine(TupleStore tupleStore) {
        return ZanzibarEngine.builder()
                .store(tupleStore)
                .namespace(NamespaceConfig.builder("doc")
                        .relation("parent", RewriteRule.thisRelation())
                        .relation("owner", RewriteRule.thisRelation())
                        .relation("editor", RewriteRule.union(
                                RewriteRule.thisRelation(),
                                RewriteRule.computedUserset("owner")))
                        .relation("viewer", RewriteRule.union(
                                RewriteRule.thisRelation(),
                                RewriteRule.computedUserset("editor"),
                                RewriteRule.tupleToUserset("parent", "viewer")))
                        .build())
                .namespace(NamespaceConfig.builder("folder")
                        .relation("parent", RewriteRule.thisRelation())
                        .relation("viewer", RewriteRule.union(
                                RewriteRule.thisRelation(),
                                RewriteRule.tupleToUserset("parent", "viewer")))
                        .build())
                .namespace(NamespaceConfig.builder("group")
                        .relation("member", RewriteRule.thisRelation())
                        .build())
                .build();
    }
}
