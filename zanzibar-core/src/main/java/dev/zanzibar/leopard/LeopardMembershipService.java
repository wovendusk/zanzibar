package dev.zanzibar.leopard;

import dev.zanzibar.model.ObjectRef;
import dev.zanzibar.model.SubjectRef;

import java.util.Objects;

/**
 * Routes a membership query to the fast Leopard index when it is fresh enough, and
 * to the authoritative (recursive) resolver otherwise.
 *
 * <p>This is where the denormalized index meets the consistency model. The index is
 * eventually consistent — it tails the change-feed and lags the store. A query that
 * requires a snapshot at least as fresh as revision {@code R} may be served from
 * Leopard only if {@code index.indexedThrough() >= R}; if the index is staler, the
 * service falls back to the ground-truth engine, which reads the store directly at
 * {@code R}. The accelerator is thus safe by construction: it speeds up the reads it
 * can prove it is fresh enough for, and never returns an answer staler than asked.
 */
public final class LeopardMembershipService {

    /** The ground-truth resolver used when the index is too stale (typically the CheckEngine). */
    public interface AuthoritativeResolver {
        boolean isMember(SubjectRef member, ObjectRef group, long atRevision);
    }

    public enum Source {
        /** Answered by the flattened index (fast path). */
        LEOPARD,
        /** Answered by the authoritative recursive resolver (index too stale). */
        FALLBACK
    }

    public record Answer(boolean member, Source source) {}

    private final LeopardIndex index;
    private final AuthoritativeResolver fallback;

    public LeopardMembershipService(LeopardIndex index, AuthoritativeResolver fallback) {
        this.index = Objects.requireNonNull(index);
        this.fallback = Objects.requireNonNull(fallback);
    }

    /**
     * Answer whether {@code member} is in {@code group}, needing freshness at least
     * {@code requiredRevision}. Uses Leopard if it is indexed through that revision,
     * else the authoritative resolver.
     */
    public Answer isMember(SubjectRef member, ObjectRef group, long requiredRevision) {
        if (index.indexedThrough() >= requiredRevision) {
            return new Answer(index.isMember(member, group), Source.LEOPARD);
        }
        return new Answer(fallback.isMember(member, group, requiredRevision), Source.FALLBACK);
    }
}
