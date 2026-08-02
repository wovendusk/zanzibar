package dev.zanzibar.model;

import java.util.Objects;

/**
 * A reference to a subject (user or userset) in the authorization graph.
 *
 * When {@code relation} is null, this refers to a specific object (e.g., user:alice).
 * When {@code relation} is non-null, this refers to a userset
 * (e.g., group:eng#member — all members of the eng group).
 */
public record SubjectRef(String namespace, String id, String relation) {

    public SubjectRef {
        Objects.requireNonNull(namespace, "namespace must not be null");
        Objects.requireNonNull(id, "id must not be null");
    }

    public static SubjectRef user(String namespace, String id) {
        return new SubjectRef(namespace, id, null);
    }

    public static SubjectRef userset(String namespace, String id, String relation) {
        Objects.requireNonNull(relation, "relation must not be null for userset subjects");
        return new SubjectRef(namespace, id, relation);
    }

    public ObjectRef asObjectRef() {
        return new ObjectRef(namespace, id);
    }

    public boolean isUserset() {
        return relation != null;
    }

    @Override
    public String toString() {
        return relation == null
                ? namespace + ":" + id
                : namespace + ":" + id + "#" + relation;
    }
}
