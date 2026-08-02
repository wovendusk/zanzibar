package dev.zanzibar.acl.dto;

public record CheckResponse(boolean granted, long evaluatedAtRevision) {}
