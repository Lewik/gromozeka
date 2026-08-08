ALTER TABLE workers
    ADD COLUMN kind VARCHAR(32) NOT NULL DEFAULT 'EXECUTION',
    ADD COLUMN subject_user_id VARCHAR(255) NULL REFERENCES users(id) ON DELETE RESTRICT;

ALTER TABLE workers
    ADD CONSTRAINT chk_workers_kind
        CHECK (kind IN ('EXECUTION', 'MOBILE_DEVICE')),
    ADD CONSTRAINT chk_workers_subject
        CHECK (
            (kind = 'EXECUTION' AND subject_user_id IS NULL) OR
            (kind = 'MOBILE_DEVICE' AND subject_user_id IS NOT NULL)
        );

CREATE INDEX idx_workers_subject_user
    ON workers(subject_user_id)
    WHERE subject_user_id IS NOT NULL;

CREATE TABLE context_state_events (
    id VARCHAR(255) PRIMARY KEY,
    ingest_order BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE,
    user_id VARCHAR(255) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    source_kind VARCHAR(32) NOT NULL,
    source_id VARCHAR(255) NOT NULL,
    subject_kind VARCHAR(32) NOT NULL,
    subject_id VARCHAR(255) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    projection_key TEXT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    source_json JSONB NOT NULL,
    payload_json JSONB NOT NULL,
    CONSTRAINT chk_context_state_source_kind
        CHECK (source_kind IN ('MOBILE_WORKER', 'CLIENT', 'USER', 'SERVER')),
    CONSTRAINT chk_context_state_subject_kind
        CHECK (subject_kind IN ('USER', 'DEVICE'))
);

CREATE INDEX idx_context_state_events_user_time
    ON context_state_events(user_id, observed_at DESC, received_at DESC, ingest_order DESC);

CREATE INDEX idx_context_state_events_subject_time
    ON context_state_events(
        user_id,
        subject_kind,
        subject_id,
        observed_at DESC,
        received_at DESC,
        ingest_order DESC
    );

CREATE TABLE context_state_projections (
    user_id VARCHAR(255) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    subject_kind VARCHAR(32) NOT NULL,
    subject_id VARCHAR(255) NOT NULL,
    state_key TEXT NOT NULL,
    event_id VARCHAR(255) NOT NULL REFERENCES context_state_events(id) ON DELETE CASCADE,
    ingest_order BIGINT NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    payload_json JSONB NOT NULL,
    PRIMARY KEY (user_id, subject_kind, subject_id, state_key),
    CONSTRAINT chk_context_state_projection_subject_kind
        CHECK (subject_kind IN ('USER', 'DEVICE'))
);

CREATE INDEX idx_context_state_projections_event
    ON context_state_projections(event_id);
