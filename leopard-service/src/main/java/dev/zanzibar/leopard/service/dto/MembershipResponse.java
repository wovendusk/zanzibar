package dev.zanzibar.leopard.service.dto;

/**
 * @param member         whether the member is in the group
 * @param source         "LEOPARD_INDEX" or "ACL_FALLBACK"
 * @param indexedThrough the revision the index had caught up to
 */
public record MembershipResponse(boolean member, String source, long indexedThrough) {
}
