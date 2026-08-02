package dev.zanzibar.config;

import java.util.List;
import java.util.Objects;

/**
 * A sealed hierarchy representing userset rewrite rules.
 * The check engine pattern-matches on these to compute permissions.
 */
public sealed interface RewriteRule {

    /** Direct tuples only — look up stored tuples for this (object, relation). */
    record This() implements RewriteRule {}

    /**
     * Same object, different relation.
     * Example: "editors are also viewers" → ComputedUserset("editor") on the viewer relation.
     */
    record ComputedUserset(String relation) implements RewriteRule {
        public ComputedUserset {
            Objects.requireNonNull(relation);
        }
    }

    /**
     * Inherit from a related object.
     * Example: "inherit viewer from parent folder" →
     *   TupleToUserset("parent", "viewer") on the document's viewer relation.
     *
     * Reads tuples for resource#tuplesetRelation to find parent objects,
     * then checks parent#computedRelation@subject for each parent.
     */
    record TupleToUserset(String tuplesetRelation, String computedRelation) implements RewriteRule {
        public TupleToUserset {
            Objects.requireNonNull(tuplesetRelation);
            Objects.requireNonNull(computedRelation);
        }
    }

    /** Access granted if ANY child rule grants. */
    record Union(List<RewriteRule> children) implements RewriteRule {
        public Union {
            Objects.requireNonNull(children);
            children = List.copyOf(children);
        }
    }

    /** Access granted only if ALL child rules grant. */
    record Intersection(List<RewriteRule> children) implements RewriteRule {
        public Intersection {
            Objects.requireNonNull(children);
            children = List.copyOf(children);
        }
    }

    /** Access granted by base, then denied by subtract. */
    record Exclusion(RewriteRule base, RewriteRule subtract) implements RewriteRule {
        public Exclusion {
            Objects.requireNonNull(base);
            Objects.requireNonNull(subtract);
        }
    }

    // Factory methods for concise config construction

    static This thisRelation() {
        return new This();
    }

    static ComputedUserset computedUserset(String relation) {
        return new ComputedUserset(relation);
    }

    static TupleToUserset tupleToUserset(String tuplesetRelation, String computedRelation) {
        return new TupleToUserset(tuplesetRelation, computedRelation);
    }

    static Union union(RewriteRule... children) {
        return new Union(List.of(children));
    }

    static Intersection intersection(RewriteRule... children) {
        return new Intersection(List.of(children));
    }

    static Exclusion exclusion(RewriteRule base, RewriteRule subtract) {
        return new Exclusion(base, subtract);
    }
}
