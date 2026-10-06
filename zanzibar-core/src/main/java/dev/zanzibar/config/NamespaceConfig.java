package dev.zanzibar.config;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The configuration of one namespace (object type): its relations and the
 * rewrite rule of each.
 *
 * <pre>
 * NamespaceConfig.builder("doc")
 *     .relation("owner", RewriteRule.thisRelation())
 *     .relation("editor", RewriteRule.union(
 *         RewriteRule.thisRelation(),
 *         RewriteRule.computedUserset("owner")))
 *     .build();
 * </pre>
 */
public record NamespaceConfig(String name, Map<String, RewriteRule> relations) {

    public NamespaceConfig {
        Objects.requireNonNull(name);
        relations = Map.copyOf(relations);
    }

    /** The rewrite rule of a relation, or null if the relation is not configured. */
    public RewriteRule ruleFor(String relation) {
        return relations.get(relation);
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public static class Builder {
        private final String name;
        private final Map<String, RewriteRule> relations = new LinkedHashMap<>();

        private Builder(String name) {
            this.name = name;
        }

        public Builder relation(String relationName, RewriteRule rule) {
            relations.put(relationName, rule);
            return this;
        }

        public NamespaceConfig build() {
            return new NamespaceConfig(name, relations);
        }
    }
}
