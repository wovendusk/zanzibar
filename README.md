# Zanzibar-style authorization engine

An access-control service modelled on Google's Zanzibar
([paper](atc19-pang.pdf)). It answers one question, "does this user have this
relation on this object?", from stored relationship tuples and per-namespace
rewrite rules, and it gives a consistency guarantee that stops a revoked user
from reading content added after the revocation.

Java 21, Spring Boot 3.4, Apache Kafka, PostgreSQL.

## Concepts

| Term | Meaning |
|---|---|
| Tuple | `object#relation@subject`, e.g. `doc:readme#viewer@user:alice` |
| Userset | A subject that is a set, e.g. `group:eng#member` (all members of group eng) |
| Namespace config | For one object type, the relations it has and a rewrite rule for each |
| Rewrite rule | One of `this`, `computed_userset`, `tuple_to_userset`, `union`, `intersection`, `exclusion` |
| Revision | A number that increases with every write; the store can be read as of any revision |
| Zookie | The revision a write was applied at, returned to the client |

## Services

```
                         ┌────────────┐
   client ──HTTP──────►  │  gateway   │  :8080   routes by URL path
                         └─────┬──────┘
          ┌────────────────────┼─────────────────────┐
          ▼                    ▼                     ▼
   ┌─────────────┐      ┌───────────────┐     ┌──────────────────────┐
   │ acl-service │      │leopard-service│     │ intelligence-service │
   │    :8081    │◄─────│     :8082     │     │        :8083         │
   └──┬───────┬──┘ HTTP └───────▲───────┘     └───▲──────────┬───────┘
      │       │   fallback      │                 │          │ HTTP
      │       │                 │                 │          ▼
      │       └──► Kafka topic "permissions.changes" ────────┘   OpenAI API
      ▼                                           
  PostgreSQL (tuples, outbox, namespace configs, audit log)
```

- **acl-service** owns the data and is the source of truth. It implements
  write, delete, read, check and expand, and publishes every change to Kafka.
- **leopard-service** keeps an in-memory index of flattened group membership,
  built from the Kafka stream, so nested-group questions are a lookup.
- **intelligence-service** stores an audit log from the same stream, explains
  check decisions in English and compiles English policies into namespace
  configs, using an LLM.
- **gateway** is the single entry point and forwards each request to the
  service that handles its path.

`zanzibar-core` is a plain Java library with the engine itself; it has no
Spring or Kafka in it and holds the unit tests.

## How it works

### Append-only storage

The `tuples` table is never updated. A write inserts a row with
`active = true`, a delete inserts a row with `active = false`, and each row
takes the next value of a sequence as its revision. Reading "as of revision R"
means taking, for each tuple, its newest row at or below R. Every past state
therefore stays readable.

Writes run one at a time (the transaction locks the table against other
writers), so revisions become visible in order.

### Check

`CheckEngine` walks the rewrite rule of the requested relation together with
the stored tuples: a union tries each branch, a `computed_userset` re-checks
another relation on the same object, a `tuple_to_userset` follows a tuple to
another object (document to parent folder) and checks there, and a userset
subject (`group:eng#member`) leads to a check on that group. Questions already
in progress are skipped, so groups that contain each other do not loop.

### Consistency: zookies and the new-enemy problem

The failure to prevent: Bob is removed from a document's viewers, then new
content is added, and a check for Bob is answered from a state that predates
his removal. He sees content he was never meant to see.

Every write returns a zookie. The client stores the zookie of a content change
with the content and sends it with later checks. The engine then evaluates the
check at a snapshot at least as fresh as the zookie, which necessarily
includes any removal made before the content changed.

This matters here because the system has a component that runs behind: the
Leopard index is fed through Kafka. A membership request carrying a zookie the
index has not caught up to is not answered from the index; it is passed to the
ACL service instead (`"source": "ACL_FALLBACK"` in the response).

### Cache and quantization

Check results are cached under (object, relation, subject, revision). Because
the store is append-only, an answer at a given revision never becomes wrong,
so nothing is ever invalidated; entries for revisions that can no longer be
chosen are evicted.

Without a zookie, a check is evaluated at the latest revision rounded down to
a multiple of `zanzibar.quantum`. Checks made close together then share a
revision and so share cache entries, at the cost of possibly not seeing the
last few writes. The default quantum of 1 turns rounding off.

### Change stream and outbox

A tuple write and its change event are saved in one database transaction: the
event goes into an `outbox` table. A scheduled publisher sends outbox rows to
Kafka in order and marks each as sent after Kafka confirms it. A change can
therefore never be stored without its event eventually being published.

Delivery is at-least-once, so consumers tolerate repeats: applying an event to
the Leopard index twice changes nothing, and the audit log ignores a revision
it already has. The topic has one partition, which keeps all changes in
revision order.

### LLM layer

The model never makes an access decision.

- **Explain**: the ACL service runs the check and returns a trace of the steps
  it took. The model rewrites the trace as a few English sentences. The
  granted/denied value in the response comes from the engine.
- **Compile policy**: the model turns an English policy into a JSON namespace
  config built from the six rule kinds. The ACL service validates the JSON;
  if it is rejected, the reason is sent back to the model for another attempt
  (three at most). Only a config that passes validation can be installed.

Validation guarantees a config is well formed. It cannot tell whether a
well-formed config means what the author intended, so a compiled config should
be read before it is installed.

## Running it

Needs JDK 21 and Docker.

```bash
docker compose up -d          # PostgreSQL and Kafka
./gradlew build               # compiles and runs the unit tests

java -jar acl-service/build/libs/acl-service-0.1.0.jar
java -jar leopard-service/build/libs/leopard-service-0.1.0.jar
java -jar intelligence-service/build/libs/intelligence-service-0.1.0.jar
java -jar gateway/build/libs/gateway-0.1.0.jar
```

The explain and compile endpoints need an OpenAI key, either as the
environment variable `OPENAI_API_KEY` or as a line `OPENAI_API_KEY=...` in a
`.env` file in the directory the intelligence service is started from. The
model is `gpt-4o-mini` and can be changed in the service's `application.yml`.

## API

All paths are under `http://localhost:8080/api/v1`.

| Method and path | Purpose |
|---|---|
| `POST /tuples` | Write a tuple; returns its zookie |
| `DELETE /tuples` | Delete a tuple; returns its zookie |
| `GET /tuples?resourceNs=&resourceId=&relation=` | Read stored tuples |
| `POST /check` | Check; optional `zookieRevision` |
| `POST /check/explain` | Check and return the decision trace |
| `POST /expand` | Everyone who holds a relation, as a tree |
| `GET /namespaces` | Names of the namespaces in force |
| `POST /namespaces/validate` | Validate a namespace config document |
| `PUT /namespaces` | Validate and install a namespace config document |
| `POST /membership/check` | Group membership from the Leopard index; optional `zookieRevision` |
| `GET /membership/freshness` | Revision the index has caught up to |
| `POST /explain` | Check and explain the decision in English |
| `POST /policy/compile` | Compile an English policy; `"install": true` to apply it |
| `GET /audit/resource`, `GET /audit/subject` | History of changes |

Example:

```bash
API=http://localhost:8080/api/v1

# alice is in group backend, backend is nested in eng,
# eng can view folder engineering, doc spec is in that folder
curl -X POST $API/tuples -H 'Content-Type: application/json' -d \
 '{"resourceNs":"group","resourceId":"backend","relation":"member","subjectNs":"user","subjectId":"alice"}'
curl -X POST $API/tuples -H 'Content-Type: application/json' -d \
 '{"resourceNs":"group","resourceId":"eng","relation":"member","subjectNs":"group","subjectId":"backend","subjectRel":"member"}'
curl -X POST $API/tuples -H 'Content-Type: application/json' -d \
 '{"resourceNs":"folder","resourceId":"engineering","relation":"viewer","subjectNs":"group","subjectId":"eng","subjectRel":"member"}'
curl -X POST $API/tuples -H 'Content-Type: application/json' -d \
 '{"resourceNs":"doc","resourceId":"spec","relation":"parent","subjectNs":"folder","subjectId":"engineering"}'

curl -X POST $API/check -H 'Content-Type: application/json' -d \
 '{"resourceNs":"doc","resourceId":"spec","relation":"viewer","subjectNs":"user","subjectId":"alice"}'
# {"granted":true,"evaluatedAtRevision":4}
```

## Limits

This is a single-node implementation of the paper's ideas, not of its scale.

- One PostgreSQL instance and one Kafka broker; no replication or sharding.
- Revisions come from a database sequence with writes serialized by a lock.
  Zanzibar gets globally ordered timestamps from Spanner's TrueTime without
  serializing writes.
- Check evaluation is a sequential recursive walk in one process. Zanzibar
  fans sub-checks out across servers and deduplicates and hedges them.
- The Leopard index covers one membership relation (`group#member`), rebuilds
  its flattened table on every nesting change, and is rebuilt from the start
  of the topic on restart.
- The check cache is per process and bounded only by revision-based eviction.
