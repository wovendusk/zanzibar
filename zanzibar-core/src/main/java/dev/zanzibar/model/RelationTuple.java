package dev.zanzibar.model;

import java.util.Objects;

/**
 * The unit of stored authorization data: object#relation@subject.
 * Example: doc:readme#viewer@user:alice
 */
public record RelationTuple(ObjectRef resource, String relation, SubjectRef subject) {

    public RelationTuple {
        Objects.requireNonNull(resource, "resource must not be null");
        Objects.requireNonNull(relation, "relation must not be null");
        Objects.requireNonNull(subject, "subject must not be null");
    }

    @Override
    public String toString() {
        return resource + "#" + relation + "@" + subject;
    }
}
