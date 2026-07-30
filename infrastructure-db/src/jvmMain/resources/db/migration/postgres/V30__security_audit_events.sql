CREATE TABLE security_audit_events (
    id VARCHAR(255) PRIMARY KEY,
    occurred_at TIMESTAMPTZ NOT NULL,
    actor_user_id VARCHAR(255) NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    action VARCHAR(64) NOT NULL,
    target_type VARCHAR(64) NOT NULL,
    target_id VARCHAR(255) NOT NULL,
    project_id VARCHAR(255) NULL,
    attributes_json TEXT NOT NULL
);

CREATE INDEX idx_security_audit_events_occurred_at
    ON security_audit_events(occurred_at DESC, id DESC);

CREATE INDEX idx_security_audit_events_actor
    ON security_audit_events(actor_user_id, occurred_at DESC);

CREATE INDEX idx_security_audit_events_project
    ON security_audit_events(project_id, occurred_at DESC)
    WHERE project_id IS NOT NULL;
