package dev.zanzibar.config;

import java.util.Objects;

/**
 * Configuration for a single relation within a namespace.
 * Pairs a relation name with its rewrite rule tree.
 */
public record RelationConfig(String name, RewriteRule rewrite) {

    public RelationConfig {
        Objects.requireNonNull(name);
        Objects.requireNonNull(rewrite);
    }
}
