package dev.zanzibar.engine;

import dev.zanzibar.cache.CheckCache;
import dev.zanzibar.cache.CheckCacheKey;
import dev.zanzibar.config.NamespaceConfig;
import dev.zanzibar.config.NamespaceRegistry;
import dev.zanzibar.config.RewriteRule;
import dev.zanzibar.model.ObjectRef;
import dev.zanzibar.model.RelationTuple;
import dev.zanzibar.model.SubjectRef;
import dev.zanzibar.store.TupleStore;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Answers "does this subject have this relation on this object?" at one revision.
 *
 * The answer is found by walking two things together: the rewrite rule of the
 * relation (union, intersection, exclusion, ...) and the stored tuples
 * (group memberships, parent folders, ...). Each step may lead to another
 * check on a different object or relation, so the walk is recursive.
 */
public class CheckEngine {

    private final TupleStore store;
    private final NamespaceRegistry namespaces;
    private final CheckCache cache; // null when caching is off

    public CheckEngine(TupleStore store, NamespaceRegistry namespaces, CheckCache cache) {
        this.store = store;
        this.namespaces = namespaces;
        this.cache = cache;
    }

    /** The state of one top-level check, shared by all its recursive steps. */
    private static class Run {
        final long revision;
        final DecisionTrace trace;      // null when no trace was asked for
        final boolean useCache;
        final Set<CheckCacheKey> inProgress = new HashSet<>();
        boolean cycleCut = false;

        Run(long revision, DecisionTrace trace, boolean useCache) {
            this.revision = revision;
            this.trace = trace;
            this.useCache = useCache;
        }

        void note(String step) {
            if (trace != null) {
                trace.add(step);
            }
        }
    }

    /**
     * @param revision the snapshot to evaluate at; every read in the walk uses it
     * @param trace    collects the steps taken, or null if not wanted. A traced
     *                 check skips the cache so that the trace shows every step.
     */
    public boolean check(ObjectRef resource, String relation, SubjectRef subject,
                         long revision, DecisionTrace trace) {
        Run run = new Run(revision, trace, cache != null && trace == null);
        boolean granted = checkRelation(resource, relation, subject, run);
        run.note("RESULT: " + subject + (granted ? " HAS " : " DOES NOT HAVE ")
                + relation + " on " + resource);
        return granted;
    }

    private boolean checkRelation(ObjectRef resource, String relation, SubjectRef subject, Run run) {
        CheckCacheKey key = new CheckCacheKey(resource, relation, subject, run.revision);

        // Groups can contain each other, so the walk can come back to a question
        // it is still in the middle of answering. That branch cannot add anything.
        if (run.inProgress.contains(key)) {
            run.cycleCut = true;
            run.note("Already checking " + resource + "#" + relation + " (cycle), skipping this branch");
            return false;
        }

        if (run.useCache) {
            Boolean cached = cache.lookup(key);
            if (cached != null) {
                return cached;
            }
        }

        run.inProgress.add(key);
        boolean result;
        RewriteRule rule = ruleFor(resource.namespace(), relation);
        if (rule == null) {
            // No config for this relation: only directly stored tuples count.
            result = checkDirect(resource, relation, subject, run);
        } else {
            result = evaluate(rule, resource, relation, subject, run);
        }
        run.inProgress.remove(key);

        // A result computed after a cycle was cut may be incomplete, so only
        // results from cycle-free walks are safe to share with other checks.
        if (run.useCache && !run.cycleCut) {
            cache.store(key, result);
        }
        return result;
    }

    private RewriteRule ruleFor(String namespace, String relation) {
        NamespaceConfig config = namespaces.get(namespace);
        if (config == null) {
            return null;
        }
        return config.ruleFor(relation);
    }

    private boolean evaluate(RewriteRule rule, ObjectRef resource, String relation,
                             SubjectRef subject, Run run) {
        return switch (rule) {
            case RewriteRule.This self -> checkDirect(resource, relation, subject, run);

            case RewriteRule.ComputedUserset computed -> {
                run.note("Everyone with " + computed.relation() + " on " + resource
                        + " also has " + relation + "; checking " + computed.relation());
                yield checkRelation(resource, computed.relation(), subject, run);
            }

            case RewriteRule.TupleToUserset ttu -> {
                boolean found = false;
                List<RelationTuple> links = store.read(resource, ttu.tuplesetRelation(), run.revision);
                for (RelationTuple link : links) {
                    ObjectRef target = link.subject().asObjectRef();
                    run.note(resource + " has " + ttu.tuplesetRelation() + " " + target
                            + "; checking " + ttu.computedRelation() + " there");
                    if (checkRelation(target, ttu.computedRelation(), subject, run)) {
                        found = true;
                        break;
                    }
                }
                yield found;
            }

            case RewriteRule.Union union -> {
                run.note("Rule for " + resource + "#" + relation + " is a union: any branch may grant");
                boolean any = false;
                for (RewriteRule child : union.children()) {
                    if (evaluate(child, resource, relation, subject, run)) {
                        any = true;
                        break;
                    }
                }
                yield any;
            }

            case RewriteRule.Intersection intersection -> {
                run.note("Rule for " + resource + "#" + relation + " is an intersection: every branch must grant");
                boolean all = true;
                for (RewriteRule child : intersection.children()) {
                    if (!evaluate(child, resource, relation, subject, run)) {
                        all = false;
                        break;
                    }
                }
                yield all;
            }

            case RewriteRule.Exclusion exclusion -> {
                run.note("Rule for " + resource + "#" + relation
                        + " is an exclusion: the first branch must grant and the second must not");
                boolean inBase = evaluate(exclusion.base(), resource, relation, subject, run);
                yield inBase && !evaluate(exclusion.subtract(), resource, relation, subject, run);
            }
        };
    }

    /** Looks only at tuples stored for exactly this object and relation. */
    private boolean checkDirect(ObjectRef resource, String relation, SubjectRef subject, Run run) {
        if (store.exists(resource, relation, subject, run.revision)) {
            run.note("Found stored tuple " + resource + "#" + relation + "@" + subject);
            return true;
        }

        // The subject may be included through a userset such as group:eng#member.
        List<RelationTuple> tuples = store.read(resource, relation, run.revision);
        for (RelationTuple tuple : tuples) {
            SubjectRef granted = tuple.subject();
            if (granted.isUserset()) {
                run.note(resource + "#" + relation + " is granted to " + granted
                        + "; checking whether " + subject + " belongs to it");
                if (checkRelation(granted.asObjectRef(), granted.relation(), subject, run)) {
                    return true;
                }
            }
        }

        run.note("No stored tuple gives " + subject + " " + relation + " on " + resource);
        return false;
    }
}
