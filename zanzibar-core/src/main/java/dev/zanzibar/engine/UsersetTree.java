package dev.zanzibar.engine;

import dev.zanzibar.model.SubjectRef;

import java.util.List;
import java.util.Set;

/**
 * The result of Expand: who holds a relation on an object, and why.
 *
 * A node with operation "leaf" lists subjects found directly in tuples.
 * Any other node combines its children with the named operation
 * (union, intersection, exclusion).
 */
public record UsersetTree(String operation, Set<SubjectRef> subjects, List<UsersetTree> children) {

    public static UsersetTree leaf(Set<SubjectRef> subjects) {
        return new UsersetTree("leaf", subjects, List.of());
    }

    public static UsersetTree node(String operation, List<UsersetTree> children) {
        return new UsersetTree(operation, Set.of(), children);
    }
}
