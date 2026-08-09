CREATE TABLE mobile_worker_contact_observations (
    ingest_order BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    worker_id VARCHAR(64) NOT NULL REFERENCES workers(id) ON DELETE CASCADE,
    subject_user_id VARCHAR(255) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    request_id VARCHAR(128) NOT NULL,
    contact_kind VARCHAR(32) NOT NULL,
    app_state VARCHAR(32) NOT NULL,
    app_version VARCHAR(255) NULL,
    worker_sent_at TIMESTAMPTZ NULL,
    received_at TIMESTAMPTZ NOT NULL,
    event_count INTEGER NOT NULL,
    pending_event_count INTEGER NULL,
    CONSTRAINT chk_mobile_worker_contact_kind
        CHECK (contact_kind IN ('EVENT_BATCH', 'HEARTBEAT')),
    CONSTRAINT chk_mobile_worker_app_state
        CHECK (app_state IN ('FOREGROUND', 'BACKGROUND', 'UNKNOWN')),
    CONSTRAINT chk_mobile_worker_event_count
        CHECK (event_count >= 0),
    CONSTRAINT chk_mobile_worker_pending_event_count
        CHECK (pending_event_count IS NULL OR pending_event_count >= event_count)
);

CREATE INDEX idx_mobile_worker_contacts_worker_time
    ON mobile_worker_contact_observations(worker_id, received_at DESC, ingest_order DESC);

CREATE INDEX idx_mobile_worker_contacts_request
    ON mobile_worker_contact_observations(worker_id, request_id);

CREATE INDEX idx_mobile_worker_contacts_user_time
    ON mobile_worker_contact_observations(subject_user_id, received_at DESC, ingest_order DESC);

CREATE TABLE mobile_worker_presence (
    worker_id VARCHAR(64) PRIMARY KEY REFERENCES workers(id) ON DELETE CASCADE,
    subject_user_id VARCHAR(255) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    last_observation_order BIGINT NOT NULL,
    last_request_id VARCHAR(128) NOT NULL,
    last_contact_kind VARCHAR(32) NOT NULL,
    last_app_state VARCHAR(32) NOT NULL,
    last_app_version VARCHAR(255) NULL,
    last_worker_sent_at TIMESTAMPTZ NULL,
    last_received_at TIMESTAMPTZ NOT NULL,
    last_event_count INTEGER NOT NULL,
    last_pending_event_count INTEGER NULL,
    CONSTRAINT chk_mobile_worker_presence_contact_kind
        CHECK (last_contact_kind IN ('EVENT_BATCH', 'HEARTBEAT')),
    CONSTRAINT chk_mobile_worker_presence_app_state
        CHECK (last_app_state IN ('FOREGROUND', 'BACKGROUND', 'UNKNOWN')),
    CONSTRAINT chk_mobile_worker_presence_event_count
        CHECK (last_event_count >= 0),
    CONSTRAINT chk_mobile_worker_presence_pending_event_count
        CHECK (last_pending_event_count IS NULL OR last_pending_event_count >= last_event_count)
);

CREATE INDEX idx_mobile_worker_presence_user
    ON mobile_worker_presence(subject_user_id, last_received_at DESC);
