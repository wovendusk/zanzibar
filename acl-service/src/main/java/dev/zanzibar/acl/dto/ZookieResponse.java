package dev.zanzibar.acl.dto;

/** Returned by writes and deletes: the revision the change was applied at. */
public record ZookieResponse(long revision) {
}
