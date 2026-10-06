package dev.zanzibar;

import dev.zanzibar.cache.CheckCache;
import dev.zanzibar.config.NamespaceConfig;
import dev.zanzibar.config.NamespaceRegistry;
import dev.zanzibar.engine.CheckEngine;
import dev.zanzibar.engine.CheckResult;
import dev.zanzibar.engine.DecisionTrace;
import dev.zanzibar.engine.ExpandEngine;
import dev.zanzibar.engine.SnapshotSelector;
import dev.zanzibar.engine.UsersetTree;
import dev.zanzibar.model.ObjectRef;
import dev.zanzibar.model.RelationTuple;
import dev.zanzibar.model.SubjectRef;
import dev.zanzibar.model.Zookie;
import dev.zanzibar.store.TupleStore;

import java.util.List;

/**
 * The entry point to the authorization engine. It offers the operations of the
 * Zanzibar API (write, read, check, expand) and connects the parts that
 * implement them: tuple store, namespace configs, snapshot selection and cache.
 */
public class ZanzibarEngine {

    private final TupleStore store;
    private final NamespaceRegistry namespaces;
    private final SnapshotSelector snapshots;
    private final CheckCache cache;
    private final CheckEngine checkEngine;
    private final ExpandEngine expandEngine;

    /**
     * @param quantum how coarsely checks without a zookie are rounded to a
     *                shared revision; 1 means every check sees the latest write
     */
    public ZanzibarEngine(TupleStore store, NamespaceRegistry namespaces, long quantum) {
        this.store = store;
        this.namespaces = namespaces;
        this.snapshots = new SnapshotSelector(quantum);
        this.cache = new CheckCache();
        this.checkEngine = new CheckEngine(store, namespaces, cache);
        this.expandEngine = new ExpandEngine(store, namespaces);
    }

    // --- Writes ---

    public Zookie write(ObjectRef resource, String relation, SubjectRef subject) {
        return store.write(resource, relation, subject);
    }

    public Zookie delete(ObjectRef resource, String relation, SubjectRef subject) {
        return store.delete(resource, relation, subject);
    }

    // --- Reads ---

    /** The stored tuples for (resource, relation); zookie may be null for "latest". */
    public List<RelationTuple> read(ObjectRef resource, String relation, Zookie atLeastAsFresh) {
        long latest = store.latestRevision();
        long revision = atLeastAsFresh == null ? latest : snapshots.select(atLeastAsFresh, latest);
        return store.read(resource, relation, revision);
    }

    /**
     * Check at a snapshot at least as fresh as the zookie. With a null zookie
     * the engine picks the snapshot itself (see {@link SnapshotSelector}).
     */
    public CheckResult check(ObjectRef resource, String relation, SubjectRef subject,
                             Zookie atLeastAsFresh) {
        long latest = store.latestRevision();
        long revision = snapshots.select(atLeastAsFresh, latest);
        // No check is ever evaluated below the current rounded revision again.
        cache.evictBefore(snapshots.roundDown(latest));
        boolean granted = checkEngine.check(resource, relation, subject, revision, null);
        return new CheckResult(granted, revision, null);
    }

    /** Like check, but also records every step taken, for explaining the decision. */
    public CheckResult checkWithTrace(ObjectRef resource, String relation, SubjectRef subject,
                                      Zookie atLeastAsFresh) {
        long revision = snapshots.select(atLeastAsFresh, store.latestRevision());
        DecisionTrace trace = new DecisionTrace();
        boolean granted = checkEngine.check(resource, relation, subject, revision, trace);
        return new CheckResult(granted, revision, trace.toText());
    }

    public UsersetTree expand(ObjectRef resource, String relation, Zookie atLeastAsFresh) {
        long revision = snapshots.select(atLeastAsFresh, store.latestRevision());
        return expandEngine.expand(resource, relation, revision);
    }

    // --- Namespace configs ---

    /** Add or replace a namespace config. Cached answers may depend on the old one, so they are dropped. */
    public void registerNamespace(NamespaceConfig config) {
        namespaces.register(config);
        cache.clear();
    }

    public List<NamespaceConfig> namespaces() {
        return namespaces.all();
    }

    // --- Accessors ---

    public Zookie currentZookie() {
        return new Zookie(store.latestRevision());
    }

    public CheckCache cache() {
        return cache;
    }
}
