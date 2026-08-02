package dev.zanzibar.model;

import java.util.Objects;

/**
 * A reference to an object in the authorization graph.
 * Examples: ("doc", "readme"), ("folder", "engineering"), ("group", "eng")
 */
public record ObjectRef(String namespace, String id) {

    public ObjectRef {
        Objects.requireNonNull(namespace, "namespace must not be null");
        Objects.requireNonNull(id, "id must not be null");
    }

    @Override
    public String toString() {
        return namespace + ":" + id;
    }
}
