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
