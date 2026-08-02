CREATE TABLE IF NOT EXISTS audit_log (
    id              BIGSERIAL PRIMARY KEY,
    type            TEXT NOT NULL,
    resource_ns     TEXT NOT NULL,
    resource_id     TEXT NOT NULL,
    relation        TEXT NOT NULL,
    subject_ns      TEXT NOT NULL,
    subject_id      TEXT NOT NULL,
    subject_rel     TEXT,
    revision        BIGINT NOT NULL,
    event_timestamp TIMESTAMPTZ NOT NULL,
    created_at      TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_audit_resource
    ON audit_log(resource_ns, resource_id, revision DESC);

CREATE INDEX IF NOT EXISTS idx_audit_subject
    ON audit_log(subject_ns, subject_id, revision DESC);
