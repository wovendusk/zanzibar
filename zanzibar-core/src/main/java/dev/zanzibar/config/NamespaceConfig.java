package dev.zanzibar.config;

import java.util.*;

/**
 * Configuration for a namespace (object type) defining its relations
 * and their rewrite rules.
 *
 * Built via the fluent builder API:
 * <pre>
 * NamespaceConfig.builder("doc")
 *     .relation("owner", RewriteRule.thisRelation())
 *     .relation("editor", RewriteRule.union(
 *         RewriteRule.thisRelation(),
 *         RewriteRule.computedUserset("owner")))
 *     .build();
 * </pre>
 */
public record NamespaceConfig(String name, Map<String, RelationConfig> relations) {

    public NamespaceConfig {
        Objects.requireNonNull(name);
        Objects.requireNonNull(relations);
        relations = Map.copyOf(relations);
    }

    public RelationConfig getRelation(String relationName) {
        return relations.get(relationName);
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public static class Builder {
        private final String name;
        private final Map<String, RelationConfig> relations = new LinkedHashMap<>();

        private Builder(String name) {
            this.name = name;
        }

        public Builder relation(String relationName, RewriteRule rewrite) {
            relations.put(relationName, new RelationConfig(relationName, rewrite));
            return this;
        }

        public NamespaceConfig build() {
            return new NamespaceConfig(name, relations);
        }
    }
}
