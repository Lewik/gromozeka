-- Slot ownership intentionally survives history reset/compaction and Conversation deletion.
-- Deleted/unavailable Conversations leave leases for explicit human-confirmed reclaim.
CREATE TABLE development_slots (
    number BIGSERIAL PRIMARY KEY,
    id TEXT NOT NULL UNIQUE,
    user_id VARCHAR(255) NOT NULL,
    workspace_id VARCHAR(255) NOT NULL,
    retired BOOLEAN NOT NULL DEFAULT FALSE,
    record_json JSONB NOT NULL
);
CREATE UNIQUE INDEX development_slots_live_workspace ON development_slots(workspace_id) WHERE NOT retired;
CREATE INDEX development_slots_user ON development_slots(user_id, number);

CREATE TABLE slot_leases (
    id TEXT PRIMARY KEY,
    slot_number BIGINT NOT NULL REFERENCES development_slots(number),
    conversation_id VARCHAR(255) NOT NULL,
    access TEXT NOT NULL CHECK (access IN ('READ', 'WRITE')),
    released_at TIMESTAMPTZ,
    record_json JSONB NOT NULL
);
CREATE UNIQUE INDEX slot_leases_writer ON slot_leases(slot_number) WHERE released_at IS NULL AND access = 'WRITE';
CREATE UNIQUE INDEX slot_leases_conversation ON slot_leases(slot_number, conversation_id) WHERE released_at IS NULL;
CREATE INDEX slot_leases_current ON slot_leases(conversation_id) WHERE released_at IS NULL;

CREATE TABLE slot_requests (
    id TEXT PRIMARY KEY,
    slot_number BIGINT NOT NULL REFERENCES development_slots(number),
    conversation_id VARCHAR(255) NOT NULL,
    idempotency_key TEXT NOT NULL UNIQUE,
    requested_at TIMESTAMPTZ NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('PENDING', 'GRANTED', 'CANCELLED', 'REJECTED')),
    record_json JSONB NOT NULL
);
CREATE UNIQUE INDEX slot_requests_pending_conversation ON slot_requests(slot_number, conversation_id) WHERE state = 'PENDING';
CREATE INDEX slot_requests_queue ON slot_requests(slot_number, requested_at, id) WHERE state = 'PENDING';

CREATE TABLE slot_events (
    sequence BIGSERIAL PRIMARY KEY,
    id TEXT NOT NULL UNIQUE,
    finished BOOLEAN NOT NULL DEFAULT FALSE,
    error TEXT,
    record_json JSONB NOT NULL
);
CREATE INDEX slot_events_pending ON slot_events(sequence) WHERE NOT finished;

-- Indexed Server-side metadata for accepted commands not yet represented by CommandTask.
ALTER TABLE worker_requests ADD COLUMN slot_number BIGINT;
ALTER TABLE worker_requests ADD COLUMN slot_origin_json JSONB;
CREATE INDEX worker_requests_slot_pending ON worker_requests(slot_number) WHERE completed_at IS NULL AND slot_number IS NOT NULL;
