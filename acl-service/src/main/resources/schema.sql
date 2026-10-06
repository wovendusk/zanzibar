CREATE SEQUENCE IF NOT EXISTS revision_seq;

-- Append-only: rows are only ever inserted. active = false marks a delete.
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

CREATE INDEX IF NOT EXISTS idx_tuples_lookup
    ON tuples(resource_ns, resource_id, relation,
              subject_ns, subject_id, revision DESC);

-- Events waiting to be sent to Kafka, written in the same transaction as the tuple.
CREATE TABLE IF NOT EXISTS outbox (
    id         BIGSERIAL PRIMARY KEY,
    msg_key    TEXT NOT NULL,
    payload    TEXT NOT NULL,
    published  BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_outbox_pending ON outbox(id) WHERE published = false;

-- Namespace configs installed through the API, as JSON text.
CREATE TABLE IF NOT EXISTS namespace_configs (
    name        TEXT PRIMARY KEY,
    config_json TEXT NOT NULL
);
