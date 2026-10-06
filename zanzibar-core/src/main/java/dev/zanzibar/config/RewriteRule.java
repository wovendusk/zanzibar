package dev.zanzibar.config;

import java.util.List;
import java.util.Objects;

/**
 * A userset rewrite rule: how the set of subjects holding a relation is computed.
 * The six kinds below are the only ones that exist, so the check and expand
 * engines can switch over them and the compiler verifies every kind is handled.
 */
public sealed interface RewriteRule {

    /** The subjects stored directly in tuples for this object and relation. */
    record This() implements RewriteRule {
    }

    /** Everyone holding another relation on the same object ("editors are also viewers"). */
    record ComputedUserset(String relation) implements RewriteRule {
        public ComputedUserset {
            Objects.requireNonNull(relation);
        }
    }

    /**
     * Follow a relation to other objects and take a relation there.
     * TupleToUserset("parent", "viewer") on a doc means "viewers of my parent folder".
     */
    record TupleToUserset(String tuplesetRelation, String computedRelation) implements RewriteRule {
        public TupleToUserset {
            Objects.requireNonNull(tuplesetRelation);
            Objects.requireNonNull(computedRelation);
        }
    }

    /** Granted if any child grants. */
    record Union(List<RewriteRule> children) implements RewriteRule {
        public Union {
            children = List.copyOf(children);
        }
    }

    /** Granted only if every child grants. */
    record Intersection(List<RewriteRule> children) implements RewriteRule {
        public Intersection {
            children = List.copyOf(children);
        }
    }

    /** Granted if base grants and subtract does not. */
    record Exclusion(RewriteRule base, RewriteRule subtract) implements RewriteRule {
        public Exclusion {
            Objects.requireNonNull(base);
            Objects.requireNonNull(subtract);
        }
    }

    static RewriteRule thisRelation() {
        return new This();
    }

    static RewriteRule computedUserset(String relation) {
        return new ComputedUserset(relation);
    }

    static RewriteRule tupleToUserset(String tuplesetRelation, String computedRelation) {
        return new TupleToUserset(tuplesetRelation, computedRelation);
    }

    static RewriteRule union(RewriteRule... children) {
        return new Union(List.of(children));
    }

    static RewriteRule intersection(RewriteRule... children) {
        return new Intersection(List.of(children));
    }

    static RewriteRule exclusion(RewriteRule base, RewriteRule subtract) {
        return new Exclusion(base, subtract);
    }
}
