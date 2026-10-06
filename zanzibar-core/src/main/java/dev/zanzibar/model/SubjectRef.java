package dev.zanzibar.model;

import java.util.Objects;

/**
 * Who a tuple grants a relation to.
 *
 * When relation is null this is a single object, e.g. user:alice.
 * When relation is set this is a userset, e.g. group:eng#member
 * ("everyone who is a member of group eng").
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
        Objects.requireNonNull(relation, "relation must not be null for a userset");
        return new SubjectRef(namespace, id, relation);
    }

    public boolean isUserset() {
        return relation != null;
    }

    public ObjectRef asObjectRef() {
        return new ObjectRef(namespace, id);
    }

    @Override
    public String toString() {
        if (relation == null) {
            return namespace + ":" + id;
        }
        return namespace + ":" + id + "#" + relation;
    }
}
