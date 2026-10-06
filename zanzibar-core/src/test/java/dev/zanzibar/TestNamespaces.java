package dev.zanzibar;

import dev.zanzibar.config.NamespaceConfig;
import dev.zanzibar.config.NamespaceRegistry;
import dev.zanzibar.config.RewriteRule;

/** The namespace configs shared by the tests: groups, folders and documents. */
final class TestNamespaces {

    private TestNamespaces() {
    }

    static NamespaceRegistry standard() {
        NamespaceRegistry registry = new NamespaceRegistry();
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
                .relation("banned", RewriteRule.thisRelation())
                .relation("cleared", RewriteRule.thisRelation())
                .relation("editor", RewriteRule.union(
                        RewriteRule.thisRelation(),
                        RewriteRule.computedUserset("owner")))
                .relation("viewer", RewriteRule.union(
                        RewriteRule.thisRelation(),
                        RewriteRule.computedUserset("editor"),
                        RewriteRule.tupleToUserset("parent", "viewer")))
                // viewer, unless banned
                .relation("reader", RewriteRule.exclusion(
                        RewriteRule.computedUserset("viewer"),
                        RewriteRule.computedUserset("banned")))
                // must be both a viewer and cleared
                .relation("auditor", RewriteRule.intersection(
                        RewriteRule.computedUserset("viewer"),
                        RewriteRule.computedUserset("cleared")))
                .build());
        return registry;
    }
}
