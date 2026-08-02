package dev.zanzibar.engine;

import dev.zanzibar.cache.CheckCache;
import dev.zanzibar.config.NamespaceConfig;
import dev.zanzibar.config.RelationConfig;
import dev.zanzibar.config.RewriteRule;
import dev.zanzibar.consistency.CheckOutcome;
import dev.zanzibar.consistency.Consistency;
import dev.zanzibar.consistency.ConsistencyPolicy;
import dev.zanzibar.model.ObjectRef;
import dev.zanzibar.model.RelationTuple;
import dev.zanzibar.model.SubjectRef;
import dev.zanzibar.model.Zookie;
import dev.zanzibar.store.TupleStore;
import dev.zanzibar.trace.TraceCollector;

import java.util.*;

/**
 * The core check algorithm. Evaluates whether a subject has a given relation
 * to a resource, walking the rewrite rule tree and the tuple graph.
 */
public class CheckEngine {

    private final TupleStore store;
    private final Map<String, NamespaceConfig> configs;
    private final CheckCache cache;
    private final ConsistencyPolicy policy;

    public CheckEngine(TupleStore store, Map<String, NamespaceConfig> configs,
                       CheckCache cache, ConsistencyPolicy policy) {
        this.store = Objects.requireNonNull(store);
        this.configs = Objects.requireNonNull(configs);
        this.cache = cache;
        this.policy = policy != null ? policy : ConsistencyPolicy.noQuantization();
    }

    public CheckEngine(TupleStore store, Map<String, NamespaceConfig> configs, CheckCache cache) {
        this(store, configs, cache, null);
    }

    public CheckEngine(TupleStore store, Map<String, NamespaceConfig> configs) {
        this(store, configs, null, null);
    }

    // --- Consistency-aware API ---

    /**
     * Check under an explicit consistency requirement. Resolves the requirement to
     * a single evaluation revision, evaluates the whole recursive check at that
     * snapshot, and reports both the answer and the revision it was evaluated at.
     */
    public CheckOutcome check(ObjectRef resource, String relation, SubjectRef subject,
                              Consistency consistency, TraceCollector trace) {
        long evalRevision = policy.resolve(consistency, store.safeRevision(), store.latestRevision());
        // The resolved revision is the effective snapshot for the entire recursion;
        // every recursive read and every cache access uses exactly this revision.
        Zookie effective = new Zookie(evalRevision);
        boolean result = checkInternal(resource, relation, subject, effective, new HashSet<>(), trace);
        if (trace != null) {
            trace.recordResult(resource, relation, subject, result);
        }
        return new CheckOutcome(result, evalRevision);
    }

    public CheckOutcome check(ObjectRef resource, String relation, SubjectRef subject,
                              Consistency consistency) {
        return check(resource, relation, subject, consistency, null);
    }

    // --- Zookie convenience API (exact-snapshot semantics) ---

    /** Check at exactly the zookie's revision (no tracing). */
    public boolean check(ObjectRef resource, String relation, SubjectRef subject, Zookie zookie) {
        return check(resource, relation, subject, zookie, null);
    }

    /** Check at exactly the zookie's revision, with optional tracing. */
    public boolean check(ObjectRef resource, String relation, SubjectRef subject,
                         Zookie zookie, TraceCollector trace) {
        return check(resource, relation, subject, new Consistency.AtExactSnapshot(zookie), trace).granted();
    }

    private boolean checkInternal(ObjectRef resource, String relation, SubjectRef subject,
                                  Zookie zookie, Set<CheckKey> visited, TraceCollector trace) {
        var key = new CheckKey(resource, relation, subject);
        if (!visited.add(key)) {
            return false;
        }

        long rev = zookie.revision();

        if (cache != null) {
            var cached = cache.lookup(resource, relation, subject, rev);
            if (cached.isPresent()) {
                if (trace != null) {
                    trace.recordCacheHit(resource, relation, subject, cached.get());
                }
                return cached.get();
            }
        }

        NamespaceConfig nsConfig = configs.get(resource.namespace());
        boolean result;

        if (nsConfig == null) {
            result = directCheck(resource, relation, subject, zookie, visited, trace);
        } else {
            RelationConfig relConfig = nsConfig.getRelation(relation);
            if (relConfig == null) {
                result = directCheck(resource, relation, subject, zookie, visited, trace);
            } else {
                result = evaluate(relConfig.rewrite(), resource, relation, subject, zookie, visited, trace);
            }
        }

        if (cache != null) {
            cache.store(resource, relation, subject, result, rev);
        }

        return result;
    }

    private boolean evaluate(RewriteRule rule, ObjectRef resource, String relation,
                             SubjectRef subject, Zookie zookie, Set<CheckKey> visited,
                             TraceCollector trace) {
        return switch (rule) {
            case RewriteRule.This t ->
                directCheck(resource, relation, subject, zookie, visited, trace);

            case RewriteRule.ComputedUserset cu -> {
                if (trace != null) {
                    trace.recordComputedUserset(resource, relation, cu.relation());
                }
                yield checkInternal(resource, cu.relation(), subject, zookie, visited, trace);
            }

            case RewriteRule.TupleToUserset ttu -> {
                List<RelationTuple> parents = store.read(resource, ttu.tuplesetRelation(), zookie.revision());
                boolean found = false;
                for (RelationTuple parentTuple : parents) {
                    ObjectRef parent = parentTuple.subject().asObjectRef();
                    if (trace != null) {
                        trace.recordTupleToUserset(resource, ttu.tuplesetRelation(),
                                parent, ttu.computedRelation());
                    }
                    if (checkInternal(parent, ttu.computedRelation(), subject, zookie, visited, trace)) {
                        found = true;
                        break;
                    }
                }
                yield found;
            }

            case RewriteRule.Union u -> {
                if (trace != null) {
                    trace.recordSetOperation("union", resource, relation);
                }
                boolean any = false;
                for (RewriteRule child : u.children()) {
                    if (evaluate(child, resource, relation, subject, zookie, visited, trace)) {
                        any = true;
                        break;
                    }
                }
                yield any;
            }

            case RewriteRule.Intersection inter -> {
                if (trace != null) {
                    trace.recordSetOperation("intersection", resource, relation);
                }
                boolean all = true;
                for (RewriteRule child : inter.children()) {
                    if (!evaluate(child, resource, relation, subject, zookie, visited, trace)) {
                        all = false;
                        break;
                    }
                }
                yield all;
            }

            case RewriteRule.Exclusion ex -> {
                if (trace != null) {
                    trace.recordSetOperation("exclusion", resource, relation);
                }
                yield evaluate(ex.base(), resource, relation, subject, zookie, visited, trace) &&
                      !evaluate(ex.subtract(), resource, relation, subject, zookie, visited, trace);
            }
        };
    }

    private boolean directCheck(ObjectRef resource, String relation, SubjectRef subject,
                                Zookie zookie, Set<CheckKey> visited, TraceCollector trace) {
        long rev = zookie.revision();

        if (store.exists(resource, relation, subject, rev)) {
            if (trace != null) {
                trace.recordDirectCheck(resource, relation, subject, true);
            }
            return true;
        }

        List<RelationTuple> tuples = store.read(resource, relation, rev);
        for (RelationTuple tuple : tuples) {
            SubjectRef tupleSubject = tuple.subject();
            if (tupleSubject.isUserset()) {
                if (trace != null) {
                    trace.recordGroupIndirection(resource, relation, tupleSubject, subject, false);
                }
                if (checkInternal(tupleSubject.asObjectRef(), tupleSubject.relation(),
                        subject, zookie, visited, trace)) {
                    if (trace != null) {
                        trace.recordGroupIndirection(resource, relation, tupleSubject, subject, true);
                    }
                    return true;
                }
            }
        }

        if (trace != null) {
            trace.recordDirectCheck(resource, relation, subject, false);
        }
        return false;
    }

    private record CheckKey(ObjectRef resource, String relation, SubjectRef subject) {}
}
