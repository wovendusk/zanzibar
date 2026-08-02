# Zanzibar Authorization Engine — Implementation Blueprint

**One-liner:** A relationship-based authorization service that answers
"can user X do Y on resource Z?" with correct behavior under concurrent
permission changes, including consistency guarantees that stop stale reads
from leaking access.

---

## Tech Stack

| Layer | Choice | Rationale |
|-------|--------|-----------|
| Language | Java 21 | Virtual threads for concurrent check fan-out; pattern matching for rewrite-tree traversal |
| Build | Gradle (Kotlin DSL) | Lightweight, fast incremental builds |
| Testing | JUnit 5 + jqwik (property-based) | jqwik shines in M4 for random-sequence invariant checking |
| API | gRPC with protobuf | Matches the real Zanzibar API surface; easy to demo |
| Persistence (M1–M2) | In-memory (`ConcurrentSkipListMap`) | Ordered by revision for snapshot reads; zero external deps |
| Persistence (optional M5) | SQLite / PostgreSQL | Drop-in upgrade if you want durability |

---

## Project Structure

```
zanzibar/
├── build.gradle.kts
├── settings.gradle.kts
├── proto/
│   └── zanzibar/v1/
│       ├── acl.proto              # RelationTuple, Zookie messages
│       ├── namespace_config.proto # Namespace, Relation, Rewrite messages
│       └── service.proto          # ACLService (Write, Read, Check, Expand)
├── src/main/java/dev/zanzibar/
│   ├── model/
│   │   ├── ObjectRef.java         # (namespace, objectId)
│   │   ├── SubjectRef.java        # (namespace, objectId, optionalRelation)
│   │   ├── RelationTuple.java     # (ObjectRef, relation, SubjectRef, revision)
│   │   └── Zookie.java            # opaque wrapper around a revision long
│   ├── config/
│   │   ├── NamespaceConfig.java   # namespace → list of RelationConfig
│   │   ├── RelationConfig.java    # relation name + RewriteRule tree
│   │   └── RewriteRule.java       # sealed interface: Union | Intersection | Exclusion
│   │                              #   | This | ComputedUserset | TupleToUserset
│   ├── store/
│   │   ├── TupleStore.java        # interface
│   │   ├── InMemoryTupleStore.java # ConcurrentSkipListMap impl
│   │   └── TupleFilter.java       # query predicate (object, relation, user, revision≤)
│   ├── engine/
│   │   ├── CheckEngine.java       # Check(object#relation, subject, zookie) → bool
│   │   ├── ExpandEngine.java      # Expand(object#relation) → UsersetTree
│   │   ├── RewriteEvaluator.java  # walks the RewriteRule tree
│   │   └── UsersetTree.java       # result tree for Expand
│   ├── cache/
│   │   ├── CheckCache.java        # (subgraph-hash, revision) → bool
│   │   └── SubgraphHasher.java    # deterministic hash of the check sub-DAG
│   ├── service/
│   │   └── AclService.java        # gRPC service wiring
│   └── ZanzibarApp.java           # main entry point
└── src/test/java/dev/zanzibar/
    ├── store/
    │   └── InMemoryTupleStoreTest.java
    ├── engine/
    │   ├── CheckEngineTest.java
    │   ├── ExpandEngineTest.java
    │   └── RewriteEvaluatorTest.java
    ├── consistency/
    │   ├── NewEnemyTest.java       # the headline test
    │   └── ConcurrentConsistencyTest.java
    └── property/
        └── ConsistencyPropertyTest.java  # jqwik
```

---

## Milestone 1 — Tuple Store + Basic Check

**Goal:** Store tuples, answer direct-membership checks, handle simple
group indirection. Shippable as a standalone demo.

### Data Model

```java
// The atomic unit of authorization
record RelationTuple(
    ObjectRef  resource,   // e.g. ("doc", "readme")
    String     relation,   // e.g. "viewer"
    SubjectRef subject,    // e.g. ("user", "aritra") or ("group", "eng", "member")
    long       revision    // monotonic, assigned by the store on write
) {}

record ObjectRef(String namespace, String id) {}

// optionalRelation is non-null for userset subjects like group:eng#member
record SubjectRef(String namespace, String id, String relation) {}
```

### Tuple Store

```
Interface TupleStore:
  long       write(RelationTuple tuple)       // returns assigned revision
  long       delete(ObjectRef, String relation, SubjectRef)
  List<...>  read(TupleFilter filter)          // exact-match + prefix queries
  long       latestRevision()
```

**InMemoryTupleStore** internals:

- Primary index: `ConcurrentSkipListMap<CompositeKey, RelationTuple>`
  where `CompositeKey = (namespace, objectId, relation, subjectKey, revision)`.
  The skip-list's ordering gives O(log n) prefix scans by (object, relation).
- Secondary index (for reverse lookups):
  `ConcurrentHashMap<SubjectKey, Set<CompositeKey>>` — needed when
  checking "which objects grant this user access?"
- `AtomicLong revisionCounter` — incremented on every write.

### Basic Check Algorithm

```
Check(resource, relation, subject):
  // 1. Direct membership
  tuples = store.read(resource, relation, subject)
  if tuples is non-empty → return true

  // 2. Group indirection (subject is a userset reference)
  tuples = store.read(resource, relation, *)  // all subjects for this object#relation
  for each tuple where tuple.subject has a relation component:
      // e.g. subject = group:eng#member → check if user is a member of group:eng
      if Check(tuple.subject.asObjectRef(), tuple.subject.relation, subject):
          return true

  return false
```

This handles:
- `doc:readme#viewer@user:aritra` — direct check
- `doc:readme#viewer@group:eng#member` — aritra is viewer if aritra is
  member of group:eng

### Tests (M1)

| Test | Asserts |
|------|---------|
| Direct grant | write tuple, check → true |
| No grant | check without tuple → false |
| Revocation | write then delete, check → false |
| Group indirection | doc#viewer@group#member + group#member@user → check user on doc → true |
| Nested groups | group-in-group indirection resolves correctly |
| Cycle detection | A#member@B#member, B#member@A#member → terminates, returns false |

### Deliverables

- [ ] `RelationTuple`, `ObjectRef`, `SubjectRef` records
- [ ] `InMemoryTupleStore` with both indexes
- [ ] `CheckEngine` with direct + group-indirection logic
- [ ] Cycle detection (visited-set passed through recursion)
- [ ] Full test suite for the above

---

## Milestone 2 — Userset Rewrites

**Goal:** Config-driven permission computation. `Check` walks a rewrite
tree, enabling role hierarchies (`owner ⊂ editor ⊂ viewer`) and cross-
object inheritance (`inherit from parent folder`).

### Config Model

```java
sealed interface RewriteRule {
    record This()                                       implements RewriteRule {}
    record ComputedUserset(String relation)             implements RewriteRule {}
    record TupleToUserset(String tuplesetRelation,
                          String computedRelation)      implements RewriteRule {}
    record Union(List<RewriteRule> children)             implements RewriteRule {}
    record Intersection(List<RewriteRule> children)      implements RewriteRule {}
    record Exclusion(RewriteRule base, RewriteRule sub)  implements RewriteRule {}
}
```

Example config (YAML parsed into the model):

```yaml
namespaces:
  - name: doc
    relations:
      - name: owner
        rewrite: this            # direct tuples only
      - name: editor
        rewrite:
          union:
            - this               # direct editors
            - computed_userset: owner   # owners are also editors
      - name: viewer
        rewrite:
          union:
            - this
            - computed_userset: editor  # editors are also viewers
            - tuple_to_userset:         # inherit from parent folder
                tupleset: parent
                computed: viewer
  - name: folder
    relations:
      - name: viewer
        rewrite: this
      - name: parent             # points to parent folder
        rewrite: this
```

### Rewrite-Aware Check

```
Check(resource, relation, subject, config):
  rule = config.getRewriteRule(resource.namespace, relation)
  return evaluate(rule, resource, relation, subject)

evaluate(rule, resource, relation, subject):
  switch rule:
    case This:
        // same as M1: direct tuples + group indirection
        return directCheck(resource, relation, subject)

    case ComputedUserset(targetRelation):
        // "editors are also viewers" → check the same object, different relation
        return Check(resource, targetRelation, subject)

    case TupleToUserset(tuplesetRelation, computedRelation):
        // "inherit viewer from parent folder"
        // 1. Find all tuples: resource#tuplesetRelation → get the related objects
        parentTuples = store.read(resource, tuplesetRelation, *)
        // 2. For each parent, check parent#computedRelation@subject
        return parentTuples.any(t → Check(t.subject.asObjectRef(), computedRelation, subject))

    case Union(children):
        return children.any(child → evaluate(child, resource, relation, subject))

    case Intersection(children):
        return children.all(child → evaluate(child, resource, relation, subject))

    case Exclusion(base, subtract):
        return evaluate(base, ...) && !evaluate(subtract, ...)
```

### Expand

```
Expand(resource, relation) → UsersetTree:
  Returns the full tree of users/sets that have the given relation.
  Each node is either a Leaf(subjects) or an Intermediate(operation, children).
  This is distinct from Read — Read returns raw tuples, Expand evaluates
  the rewrite rules and returns the computed result.
```

### Tests (M2)

| Test | Asserts |
|------|---------|
| Role hierarchy | owner → check as viewer → true |
| Folder inheritance | folder#viewer@user + doc#parent@folder → doc#viewer@user → true |
| Intersection | user must satisfy both branches |
| Exclusion | banned user excluded even if otherwise granted |
| Deep nesting | 3+ levels of folder inheritance resolve correctly |
| Expand correctness | Expand returns all effective users including inherited |

### Deliverables

- [ ] `RewriteRule` sealed hierarchy
- [ ] `NamespaceConfig` + YAML parser
- [ ] `RewriteEvaluator` — the `evaluate` switch above
- [ ] Updated `CheckEngine` integrating rewrite evaluation
- [ ] `ExpandEngine`
- [ ] Test suite covering all rewrite operations

---

## Milestone 3 — Versioning + Zookies + Consistency

**Goal:** Every write produces a monotonic revision. Checks accept a
zookie specifying a minimum freshness bound. The headline test: prove the
new enemy problem is solved.

### Zookie Design

```java
// Opaque to the caller; internally wraps a revision number.
// In production Zanzibar this is an encoded Spanner timestamp;
// here it's a monotonic long.
record Zookie(long revision) {
    byte[] encode() { ... }          // opaque bytes for the API surface
    static Zookie decode(byte[]) { ... }
}
```

### Write Returns a Zookie

```
Write(tuple) → Zookie:
  revision = store.write(tuple)   // AtomicLong.incrementAndGet()
  return new Zookie(revision)
```

### Snapshot-Consistent Check

```
Check(resource, relation, subject, zookie):
  // All reads in this check tree use revision ≤ zookie.revision
  // This means: "evaluate permissions as of at least this point in time"
  tuples = store.read(filter, maxRevision = zookie.revision)
  ...
```

The `InMemoryTupleStore` already uses a `ConcurrentSkipListMap` keyed
partly by revision. Snapshot reads filter to `revision ≤ zookie.revision`
by using `headMap`.

For soft-deleted tuples (from `delete()`), the store writes a tombstone
at the new revision rather than physically removing the entry. Snapshot
reads at older revisions still see the tuple; reads at newer revisions
see the tombstone and skip it.

### The New Enemy Test

This is the single most important test in the project:

```
Scenario: "Alice removes Bob, then adds secret content"

1. write(doc:readme#viewer@user:bob)         → zookie_z1 (rev 1)
2. delete(doc:readme#viewer@user:bob)         → zookie_z2 (rev 2)
3. write(doc:readme#content@secret_paragraph) → zookie_z3 (rev 3)

// A stale cache or replica might still serve rev 1's state.
// But the content was created at rev 3, so any check for that
// content must use zookie_z3.

4. Check(doc:readme#viewer, user:bob, zookie_z3)  → FALSE ✓
   // Correct: at revision 3, the viewer grant is revoked (tombstoned at rev 2).

5. Check(doc:readme#viewer, user:bob, zookie_z1)  → TRUE
   // Also correct: at revision 1, the grant existed. But the application
   // would never use z1 to guard content created at z3.
```

**The invariant:** for any content created at revision R, a check using
zookie(R) will always reflect all permission changes that happened before R.

### Deliverables

- [ ] `Zookie` record with encode/decode
- [ ] Tombstone-based soft deletes in `InMemoryTupleStore`
- [ ] Snapshot-consistent reads (revision-bounded queries)
- [ ] `CheckEngine` updated to thread zookie through all recursive calls
- [ ] `Write` and `Delete` return zookies
- [ ] **NewEnemyTest** — the headline test described above
- [ ] Additional consistency tests (concurrent writes, ordering)

---

## Milestone 4 — Caching + Failure Testing

**Goal:** Check cache for performance. Concurrent stress harness to prove
no interleaving ever leaks a stale grant. Property-based testing with
jqwik.

### Check Cache

```java
class CheckCache {
    // Key: hash of the check sub-DAG (deterministic traversal of
    //      the rewrite tree + the tuples it touched)
    // Value: (boolean result, revision at which it was computed)
    ConcurrentHashMap<Long, CacheEntry> cache;

    Optional<Boolean> lookup(long subgraphHash, long minRevision) {
        var entry = cache.get(subgraphHash);
        if (entry != null && entry.revision >= minRevision)
            return Optional.of(entry.result);
        return Optional.empty();
    }
}
```

**Why stale cache entries are safe:** the zookie forces re-evaluation
when freshness is required. A cached result computed at revision 5 is
valid for any check requesting revision ≤ 5. Checks requesting revision 6+
miss the cache and recompute. This mirrors production Zanzibar's ~100:1
ratio of safe cached reads to fresh evaluations.

### SubgraphHasher

During `Check`, the evaluator collects a deterministic trace of:
- The rewrite rules traversed
- The tuple keys read

This trace is hashed to produce the cache key. Two checks that traverse
the same path through the rewrite tree and read the same tuples will
hit the same cache entry.

### Concurrent Stress Harness

```java
@Test
void noStaleGrantUnderConcurrency() {
    int WRITERS = 4, READERS = 8, OPS = 10_000;
    ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

    // Writers: randomly add/remove tuples, record (operation, zookie) pairs
    // Readers: pick a random zookie, run Check, assert result matches
    //          the expected state at that revision

    // The invariant: Check(obj, rel, user, zookie_at_rev_R) must
    // return the same result as a serial evaluation of all writes
    // up to and including revision R.
}
```

### Property-Based Testing (jqwik)

```java
@Property(tries = 1000)
void consistencyInvariant(
    @ForAll("operationSequences") List<Operation> ops
) {
    // 1. Apply ops to the engine
    // 2. After each write, record the zookie
    // 3. For each recorded zookie, run Check and compare against
    //    a simple reference model (linear scan of all ops up to that revision)
    // 4. Assert: engine result == reference result, always
}
```

This generator produces random sequences of:
- `WriteOp(resource, relation, subject)`
- `DeleteOp(resource, relation, subject)`
- `CheckOp(resource, relation, subject, zookie)`

### Deliverables

- [ ] `CheckCache` with revision-aware lookup
- [ ] `SubgraphHasher` — deterministic check-path hashing
- [ ] `CheckEngine` integration with cache (check-before-evaluate, store-after)
- [ ] Concurrent stress test (virtual threads, shared engine)
- [ ] jqwik property-based test with random operation sequences
- [ ] Cache hit-rate metrics (optional, for the README story)

---

## Implementation Order & Time Estimates

| Milestone | Est. Time | Cumulative | Shippable? |
|-----------|-----------|------------|------------|
| M1 | 2–3 days | 2–3 days | Yes — basic demo |
| M2 | 3–4 days | 5–7 days | Yes — full rewrite engine |
| M3 | 2–3 days | 7–10 days | Yes — consistency story |
| M4 | 2–3 days | 9–13 days | Yes — production-grade signal |

---

## Key Design Decisions

1. **Sealed interfaces for rewrite rules.** Pattern matching with
   exhaustiveness checking means the compiler catches missing cases.
   This is a deliberate Java 21 choice.

2. **ConcurrentSkipListMap over HashMap.** Ordered keys enable efficient
   revision-bounded range scans without copying or filtering the entire
   dataset. This is the data structure that makes zookie-based snapshot
   reads O(log n) instead of O(n).

3. **Tombstones over physical deletes.** Snapshot reads at older revisions
   must still see tuples that were later deleted. Tombstones make this
   trivial — a soft-deleted tuple at revision R is invisible to reads
   at R+ but visible to reads at R-1.

4. **Virtual threads for check fan-out.** A single Check can fan out to
   dozens of sub-checks (union branches, group indirection). Virtual
   threads make it natural to parallelize these without thread-pool
   tuning.

5. **gRPC API surface.** Matches the real Zanzibar paper's API. Makes
   the project legible to anyone who's read the paper, and demonstrates
   familiarity with the production system's interface.

---

## Milestone 5 — AI Layer (Tool-Augmented LLM)

**Goal:** The engine does all reasoning; the LLM does all translation.
Two capabilities: natural-language policy authoring and decision
explanation.

### Architecture

The LLM never decides who has access. The check engine is the ground
truth. The AI module provides:

1. **System prompts with building blocks** — the LLM's job is to select
   and compose pre-defined config patterns based on English input.
2. **Structured decision traces** — the `TraceCollector` records every
   step of a check evaluation, and the LLM translates it to English.

### Decision Trace (TraceCollector + TraceStep)

During a check, the engine records each step as a `TraceStep`:

- `DirectCheck` — looked for a stored tuple, found or not found
- `GroupIndirection` — followed a userset subject (group membership)
- `ComputedUserset` — checked a different relation on the same object
- `TupleToUserset` — followed a parent relationship to another object
- `SetOperation` — evaluated union/intersection/exclusion
- `CacheHit` — result was already cached
- `Result` — final granted/denied decision

The trace is serialized to structured text and passed to the LLM with
a system prompt that maps each step type to a natural-language pattern.

### Policy Compiler (PolicyCompilerPrompt)

The system prompt contains a "recipe book" of config building blocks:
direct access, group access, role hierarchy, folder inheritance,
exclusion, intersection. Each block has a one-line explanation and
the exact Java builder API call.

The LLM selects and composes these blocks based on the admin's English
description. The generated config is verified by running test checks
through the engine.

### Verification Loop

1. Admin describes policy in English
2. LLM generates config + tuples
3. Engine loads config, writes tuples
4. Test checks verify behavior matches intent
5. If mismatch, LLM gets failure cases and adjusts

### Deliverables

- [x] `TraceStep` sealed interface with all step types
- [x] `TraceCollector` with structured text serialization
- [x] `DecisionExplainer` — system prompt + user message builder
- [x] `PolicyCompilerPrompt` — system prompt with building blocks
- [x] `ZanzibarEngine.checkWithTrace()` and `checkAndExplain()` API
- [ ] Integration with a real LLM provider (OpenAI, Anthropic, etc.)

---

## Implementation Order & Time Estimates

| Milestone | Est. Time | Cumulative | Shippable? | Status |
|-----------|-----------|------------|------------|--------|
| M1 | 2–3 days | 2–3 days | Yes — basic demo | DONE |
| M2 | 3–4 days | 5–7 days | Yes — full rewrite engine | DONE |
| M3 | 2–3 days | 7–10 days | Yes — consistency story | DONE |
| M4 | 2–3 days | 9–13 days | Yes — production-grade signal | DONE |
| M5 | 1–2 days | 10–15 days | Yes — AI-powered admin tools | DONE (prompts) |

---

## Key Design Decisions

1. **Sealed interfaces for rewrite rules.** Pattern matching with
   exhaustiveness checking means the compiler catches missing cases.
   This is a deliberate Java 21 choice.

2. **ConcurrentHashMap with ConcurrentSkipListMap version chains.**
   The outer map provides O(1) lookup by tuple key. The inner skip-list
   provides O(log v) revision-bounded queries (where v = number of
   versions per tuple, typically 1–3). This is more practical than a
   single giant skip-list for all tuples.

3. **Tombstones over physical deletes.** Snapshot reads at older revisions
   must still see tuples that were later deleted. Tombstones make this
   trivial — a soft-deleted tuple at revision R is invisible to reads
   at R+ but visible to reads at R-1.

4. **Tool-augmented AI, not autonomous AI.** The LLM never decides
   permissions. The engine does all reasoning; the LLM only translates
   between English and structured formats. This makes the AI reliable
   with even small/cheap models.

5. **Revision-aware cache with forward-only merge.** Cache entries are
   keyed by (resource, relation, subject) and store (result, revision).
   The merge strategy ensures the cache only moves forward — newer
   entries always win.

---

## The Interview Narrative

> "Jira and Confluence have deep, inherited permission models — space
> permissions, project roles, page restrictions. I built the engine that
> answers those checks correctly, including the concurrency edge cases
> most implementations get wrong."
>
> "The interesting part isn't the CRUD — it's the rewrite tree evaluation
> and the consistency model. A check like 'can this user view this page?'
> expands into a recursive traversal of set-union and tuple-to-userset
> rules across the object graph. And the zookie mechanism ensures that
> even under concurrent permission changes, you never serve a stale grant."
>
> "I proved it with property-based testing: thousands of random operation
> sequences, every one satisfying the invariant that a check at revision R
> reflects exactly the permissions written up to R."
>
> "The AI layer is the cherry on top — admins describe policies in English,
> and the LLM compiles them into rewrite rules. But the LLM never makes
> authorization decisions. It translates; the engine decides. That's how
> you make AI reliable in a security-critical system."

---

## Project Structure (Implemented)

```
zanzibar/
├── build.gradle.kts
├── settings.gradle.kts
├── blueprint.md
├── src/main/java/dev/zanzibar/
│   ├── ZanzibarEngine.java              # Main facade
│   ├── model/
│   │   ├── ObjectRef.java               # (namespace, id)
│   │   ├── SubjectRef.java              # (namespace, id, optional relation)
│   │   ├── RelationTuple.java           # (resource, relation, subject)
│   │   └── Zookie.java                  # Consistency token
│   ├── config/
│   │   ├── RewriteRule.java             # Sealed interface: This|ComputedUserset|
│   │   │                                #   TupleToUserset|Union|Intersection|Exclusion
│   │   ├── RelationConfig.java          # (name, rewrite rule)
│   │   └── NamespaceConfig.java         # (name, relations map) + builder
│   ├── store/
│   │   ├── TupleStore.java              # Interface
│   │   └── InMemoryTupleStore.java      # ConcurrentHashMap + SkipListMap impl
│   ├── engine/
│   │   ├── CheckEngine.java             # Check algorithm with rewrite evaluation
│   │   ├── ExpandEngine.java            # Expand algorithm
│   │   └── UsersetTree.java             # Expand result tree
│   ├── cache/
│   │   ├── CheckCache.java              # Revision-aware result cache
│   │   └── CheckCacheKey.java           # Cache key record
│   ├── trace/
│   │   ├── TraceStep.java               # Decision trace step types
│   │   └── TraceCollector.java          # Trace recorder + serializer
│   └── ai/
│       ├── DecisionExplainer.java        # System prompt for why/why-not
│       └── PolicyCompilerPrompt.java     # System prompt for policy authoring
└── src/test/java/dev/zanzibar/
    ├── store/
    │   └── InMemoryTupleStoreTest.java   # 10 tests
    ├── engine/
    │   ├── CheckEngineBasicTest.java     # 7 tests (M1)
    │   ├── CheckEngineRewriteTest.java   # 6 tests (M2)
    │   └── ExpandEngineTest.java         # 4 tests
    ├── consistency/
    │   ├── NewEnemyTest.java             # 3 tests (M3 headline)
    │   └── ConcurrentConsistencyTest.java# 3 tests (M4 stress)
    ├── cache/
    │   └── CheckCacheTest.java           # 8 tests
    └── property/
        └── ConsistencyPropertyTest.java  # 200 random sequences (jqwik)
```

**Total: 42 tests, all passing.**

---

## M6 — Distributed System (Spring Boot + Kafka + PostgreSQL) ✦ PLAN

### Goal
Wrap the existing engine in a multi-service architecture:
- **ACL Service** (Port 8081) — REST API, PostgreSQL-backed tuple store, Kafka publisher
- **Leopard Service** (Port 8082) — Kafka consumer, in-memory index, REST membership queries
- **Intelligence Service** (Port 8083) — Kafka consumer, audit log, decision explanation
- **Gateway** (Port 8080) — Spring Cloud Gateway MVC, route routing

### Project Structure (multi-module Gradle)

```
zanzibar/
├── settings.gradle.kts                    (multi-module)
├── build.gradle.kts                       (common config)
├── docker-compose.yml                     (PostgreSQL + Kafka)
├── zanzibar-core/                         (existing engine, MOVED from src/)
│   ├── build.gradle.kts
│   └── src/{main,test}/java/dev/zanzibar/
├── acl-service/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── java/dev/zanzibar/acl/
│       │   ├── AclServiceApplication.java
│       │   ├── config/EngineConfig.java
│       │   ├── store/PostgreSQLTupleStore.java
│       │   ├── kafka/PermissionEventPublisher.java
│       │   ├── controller/{TupleController,CheckController}.java
│       │   └── dto/*.java
│       └── resources/{application.yml, schema.sql}
├── leopard-service/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── java/dev/zanzibar/leopard/service/
│       │   ├── LeopardServiceApplication.java
│       │   ├── kafka/PermissionChangeConsumer.java
│       │   ├── controller/MembershipController.java
│       │   └── dto/*.java
│       └── resources/application.yml
├── intelligence-service/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── java/dev/zanzibar/intelligence/
│       │   ├── IntelligenceServiceApplication.java
│       │   ├── kafka/AuditEventConsumer.java
│       │   ├── service/{AuditService,ExplainService}.java
│       │   ├── controller/{AuditController,ExplainController}.java
│       │   └── dto/*.java
│       └── resources/{application.yml, schema.sql}
└── gateway/
    ├── build.gradle.kts
    └── src/main/
        ├── java/dev/zanzibar/gateway/GatewayApplication.java
        └── resources/application.yml
```

### Phase 1: Multi-Module Restructure
1. Move `src/` → `zanzibar-core/src/`
2. Extract core build config into `zanzibar-core/build.gradle.kts`
3. Update root `settings.gradle.kts` to include all modules
4. Update root `build.gradle.kts` for common config
5. Verify all existing tests pass under new structure

### Phase 2: Shared Event Model
Add `PermissionChangeEvent` record to zanzibar-core:
```java
public record PermissionChangeEvent(
    String type,           // "WRITE" or "DELETE"
    String resourceNs, String resourceId,
    String relation,
    String subjectNs, String subjectId, String subjectRel,
    long revision, long timestamp
) {}
```

### Phase 3: ACL Service
**PostgreSQLTupleStore** implements `TupleStore`:
- Uses `JdbcTemplate` (Spring JDBC, not JPA)
- PostgreSQL sequence for monotonic revisions
- `write()`: INSERT + RETURNING revision
- `delete()`: INSERT tombstone + RETURNING revision
- `read()`: subquery to find latest entry per subject at-or-before maxRevision
- `exists()`: single row lookup with ORDER BY revision DESC LIMIT 1
- After every write/delete: publish `PermissionChangeEvent` to Kafka

**EngineConfig**: Builds `ZanzibarEngine` using `PostgreSQLTupleStore` and
namespace configs loaded from application properties.

**Controllers**:
- `POST /api/v1/tuples` → write
- `DELETE /api/v1/tuples` → delete
- `POST /api/v1/check` → check (with optional zookie)
- `POST /api/v1/expand` → expand
- `GET /api/v1/tuples` → read

**Schema** (schema.sql):
```sql
CREATE SEQUENCE IF NOT EXISTS revision_seq;
CREATE TABLE IF NOT EXISTS tuples (
    id            BIGSERIAL PRIMARY KEY,
    resource_ns   TEXT NOT NULL,
    resource_id   TEXT NOT NULL,
    relation      TEXT NOT NULL,
    subject_ns    TEXT NOT NULL,
    subject_id    TEXT NOT NULL,
    subject_rel   TEXT,
    revision      BIGINT NOT NULL UNIQUE,
    active        BOOLEAN NOT NULL,
    created_at    TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_tuples_obj_rel_rev
    ON tuples(resource_ns, resource_id, relation, revision DESC);
CREATE INDEX IF NOT EXISTS idx_tuples_exists
    ON tuples(resource_ns, resource_id, relation,
              subject_ns, subject_id, revision DESC);
```

### Phase 4: Leopard Service
- Kafka consumer on `permissions.changes`
- Instantiates existing `LeopardIndex` from zanzibar-core
- Feeds events via `applyWrite()`/`applyDelete()`
- REST API: `POST /api/v1/membership/check` → `isMember()`

### Phase 5: Intelligence Service
- Kafka consumer on `permissions.changes`
- Stores every change in `audit_log` table (PostgreSQL)
- `POST /api/v1/explain` → calls ACL Service's check-with-trace,
  builds DecisionExplainer prompt, returns structured explanation
- `GET /api/v1/audit?resource=...` → search audit log

### Phase 6: Gateway
- Spring Cloud Gateway MVC
- Routes: `/api/v1/tuples/**` → ACL, `/api/v1/check/**` → ACL,
  `/api/v1/membership/**` → Leopard, `/api/v1/explain/**` → Intelligence

### Phase 7: Docker Compose
- PostgreSQL 16 (port 5432)
- Apache Kafka (Bitnami, KRaft mode, port 9092)
- All services connect to these

### Risks Identified & Mitigations
1. **Kafka serialization**: Use Spring Kafka's `JsonSerializer`/`JsonDeserializer`
   with the `PermissionChangeEvent` record. Records are serializable by Jackson.
2. **Nullable `subjectRel`**: PostgreSQL `IS NOT DISTINCT FROM` for NULL-safe
   comparisons in the exists/read queries.
3. **Revision sequence atomicity**: PostgreSQL sequences are atomic and gap-free
   under normal operation. `nextval('revision_seq')` is safe for concurrent access.
4. **Spring Cloud Gateway MVC version**: Needs Spring Boot 3.4.x and
   Spring Cloud 2024.0.x. Using the servlet-based gateway (not reactive).
5. **Java 21 preview features**: All Spring Boot services need
   `--enable-preview` JVM arg. Set via `jvmArgs` in Gradle bootRun task.
