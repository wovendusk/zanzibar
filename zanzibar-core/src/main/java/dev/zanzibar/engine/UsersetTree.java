package dev.zanzibar.engine;

import dev.zanzibar.model.SubjectRef;

import java.util.List;
import java.util.Set;

/**
 * Result of an Expand operation.
 * A tree whose leaves are concrete subjects and whose intermediate nodes
 * represent the set operations used to compute them.
 */
public sealed interface UsersetTree {

    /** A leaf containing concrete subjects that have the relation. */
    record Leaf(Set<SubjectRef> subjects) implements UsersetTree {}

    /** An intermediate node representing a set operation. */
    record Intermediate(String operation, List<UsersetTree> children) implements UsersetTree {}

    static Leaf leaf(Set<SubjectRef> subjects) {
        return new Leaf(subjects);
    }

    static Intermediate intermediate(String operation, List<UsersetTree> children) {
        return new Intermediate(operation, children);
    }
}
