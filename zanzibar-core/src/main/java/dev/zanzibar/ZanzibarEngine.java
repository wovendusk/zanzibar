package dev.zanzibar;

import dev.zanzibar.ai.DecisionExplainer;
import dev.zanzibar.cache.CheckCache;
import dev.zanzibar.config.NamespaceConfig;
import dev.zanzibar.consistency.CheckOutcome;
import dev.zanzibar.consistency.Consistency;
import dev.zanzibar.consistency.ConsistencyPolicy;
import dev.zanzibar.engine.CheckEngine;
import dev.zanzibar.engine.ExpandEngine;
import dev.zanzibar.engine.UsersetTree;
import dev.zanzibar.leopard.LeopardIndex;
import dev.zanzibar.leopard.LeopardMembershipService;
import dev.zanzibar.model.ObjectRef;
import dev.zanzibar.model.RelationTuple;
import dev.zanzibar.model.SubjectRef;
import dev.zanzibar.model.Zookie;
import dev.zanzibar.store.InMemoryTupleStore;
import dev.zanzibar.store.TupleStore;
import dev.zanzibar.trace.TraceCollector;

import java.util.*;

/**
 * Main facade for the Zanzibar authorization engine.
 * Wires together the tuple store, config, check engine, expand engine,
 * cache, and tracing.
 */
public class ZanzibarEngine {

    private final TupleStore store;
    private final Map<String, NamespaceConfig> configs;
    private final CheckEngine checkEngine;
    private final ExpandEngine expandEngine;
    private final CheckCache cache;
    private final ConsistencyPolicy policy;

    // Optional Leopard acceleration for group-membership checks (null if disabled).
    private final String groupNamespace;
    private final String membershipRelation;
    private final LeopardIndex leopard;
    private final LeopardMembershipService membershipService;

    private ZanzibarEngine(TupleStore store, Map<String, NamespaceConfig> configs,
                           CheckCache cache, ConsistencyPolicy policy,
                           boolean enableLeopard, String groupNamespace, String membershipRelation) {
        this.store = store;
        this.configs = configs;
        this.cache = cache;
        this.policy = policy;
        this.checkEngine = new CheckEngine(store, configs, cache, policy);
        this.expandEngine = new ExpandEngine(store, configs);
        this.groupNamespace = groupNamespace;
        this.membershipRelation = membershipRelation;
        if (enableLeopard) {
            this.leopard = new LeopardIndex(groupNamespace, membershipRelation);
            // Fallback is the authoritative engine, reading the store at the exact revision.
            this.membershipService = new LeopardMembershipService(leopard,
                    (member, group, atRevision) ->
                            checkEngine.check(group, membershipRelation, member, new Zookie(atRevision)));
        } else {
            this.leopard = null;
            this.membershipService = null;
        }
    }

    // --- Write operations ---

    public Zookie write(ObjectRef resource, String relation, SubjectRef subject) {
        Zookie z = store.write(resource, relation, subject);
        if (leopard != null) {
            leopard.applyWrite(resource, relation, subject, z.revision());
        }
        return z;
    }

    public Zookie write(String resourceNs, String resourceId,
                        String relation,
                        String subjectNs, String subjectId) {
        return write(new ObjectRef(resourceNs, resourceId), relation,
                SubjectRef.user(subjectNs, subjectId));
    }

    public Zookie writeUserset(String resourceNs, String resourceId,
                               String relation,
                               String subjectNs, String subjectId, String subjectRelation) {
        return write(new ObjectRef(resourceNs, resourceId), relation,
                SubjectRef.userset(subjectNs, subjectId, subjectRelation));
    }

    public Zookie delete(ObjectRef resource, String relation, SubjectRef subject) {
        Zookie z = store.delete(resource, relation, subject);
        if (leopard != null) {
            leopard.applyDelete(resource, relation, subject, z.revision());
        }
        return z;
    }

    // --- Read operations ---

    public List<RelationTuple> read(ObjectRef resource, String relation) {
        return store.read(resource, relation, store.latestRevision());
    }

    public List<RelationTuple> read(ObjectRef resource, String relation, Zookie zookie) {
        return store.read(resource, relation, zookie.revision());
    }

    // --- Check operations ---

    public boolean check(ObjectRef resource, String relation, SubjectRef subject) {
        return checkEngine.check(resource, relation, subject,
                new Zookie(store.latestRevision()));
    }

    public boolean check(ObjectRef resource, String relation, SubjectRef subject, Zookie zookie) {
        return checkEngine.check(resource, relation, subject, zookie);
    }

    /**
     * Check under an explicit consistency requirement (bounded staleness, exact
     * snapshot, fully consistent, or minimize latency). Returns the answer plus
     * the exact revision it was evaluated at.
     */
    public CheckOutcome check(ObjectRef resource, String relation, SubjectRef subject,
                              Consistency consistency) {
        return checkEngine.check(resource, relation, subject, consistency);
    }

    /**
     * Check with tracing — returns both the result and the decision trace.
     */
    public CheckResult checkWithTrace(ObjectRef resource, String relation,
                                      SubjectRef subject, Zookie zookie) {
        TraceCollector trace = new TraceCollector();
        boolean result = checkEngine.check(resource, relation, subject, zookie, trace);
        return new CheckResult(result, trace);
    }

    /**
     * Check with tracing and generate an LLM prompt for explaining the decision.
     */
    public ExplainedCheck checkAndExplain(ObjectRef resource, String relation,
                                          SubjectRef subject, Zookie zookie,
                                          String naturalLanguageQuery) {
        TraceCollector trace = new TraceCollector();
        boolean result = checkEngine.check(resource, relation, subject, zookie, trace);
        var explainer = new DecisionExplainer();
        var prompt = explainer.buildPrompt(trace, naturalLanguageQuery);
        return new ExplainedCheck(result, trace, prompt);
    }

    // --- Group membership (Leopard-accelerated) ---

    /**
     * Answer a group-membership check under a consistency requirement, using the
     * Leopard index when it is fresh enough for the resolved revision and falling
     * back to the authoritative engine otherwise. Requires the engine to have been
     * built with {@code .enableLeopard()}.
     */
    public LeopardMembershipService.Answer membership(SubjectRef member, ObjectRef group,
                                                      Consistency consistency) {
        if (membershipService == null) {
            throw new IllegalStateException("Leopard is not enabled; build with .enableLeopard()");
        }
        long required = policy.resolve(consistency, store.safeRevision(), store.latestRevision());
        return membershipService.isMember(member, group, required);
    }

    public LeopardIndex leopardIndex() {
        return leopard;
    }

    // --- Expand ---

    public UsersetTree expand(ObjectRef resource, String relation) {
        return expandEngine.expand(resource, relation, store.latestRevision());
    }

    public UsersetTree expand(ObjectRef resource, String relation, Zookie zookie) {
        return expandEngine.expand(resource, relation, zookie.revision());
    }

    /** Expand under an explicit consistency requirement (resolves to one snapshot). */
    public UsersetTree expand(ObjectRef resource, String relation, Consistency consistency) {
        long rev = policy.resolve(consistency, store.safeRevision(), store.latestRevision());
        return expandEngine.expand(resource, relation, rev);
    }

    // --- Accessors ---

    public Zookie currentZookie() {
        return new Zookie(store.latestRevision());
    }

    public CheckCache getCache() {
        return cache;
    }

    public TupleStore getStore() {
        return store;
    }

    // --- Result types ---

    public record CheckResult(boolean granted, TraceCollector trace) {}

    public record ExplainedCheck(boolean granted, TraceCollector trace,
                                 DecisionExplainer.PromptPair prompt) {}

    // --- Builder ---

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private TupleStore store;
        private final Map<String, NamespaceConfig> configs = new HashMap<>();
        private boolean enableCache = true;
        private long quantum = 1;
        private boolean enableLeopard = false;
        private String groupNamespace = "group";
        private String membershipRelation = "member";

        public Builder store(TupleStore store) {
            this.store = store;
            return this;
        }

        public Builder namespace(NamespaceConfig config) {
            this.configs.put(config.name(), config);
            return this;
        }

        public Builder disableCache() {
            this.enableCache = false;
            return this;
        }

        /**
         * Bucket size for coalescing staleness-tolerant evaluation timestamps.
         * Larger quantum → more cache reuse, more staleness. Default 1 (no coalescing).
         */
        public Builder quantum(long quantum) {
            this.quantum = quantum;
            return this;
        }

        /** Maintain a Leopard membership index (default group namespace "group", relation "member"). */
        public Builder enableLeopard() {
            this.enableLeopard = true;
            return this;
        }

        /** Maintain a Leopard membership index for a custom group namespace and relation. */
        public Builder enableLeopard(String groupNamespace, String membershipRelation) {
            this.enableLeopard = true;
            this.groupNamespace = groupNamespace;
            this.membershipRelation = membershipRelation;
            return this;
        }

        public ZanzibarEngine build() {
            if (store == null) {
                store = new InMemoryTupleStore();
            }
            CheckCache cache = enableCache ? new CheckCache() : null;
            return new ZanzibarEngine(
                    store,
                    Collections.unmodifiableMap(new HashMap<>(configs)),
                    cache,
                    ConsistencyPolicy.withQuantum(quantum),
                    enableLeopard, groupNamespace, membershipRelation);
        }
    }
}
