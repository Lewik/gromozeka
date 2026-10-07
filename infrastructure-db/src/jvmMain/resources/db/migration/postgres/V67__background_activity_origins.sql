-- Provenance is private Server state, not part of the Worker command protocol.
-- It outlives the delivery request and is removed with its conversation.
CREATE TABLE background_activity_origins (
    kind TEXT NOT NULL CHECK (kind IN ('COMMAND', 'MONITOR')),
    activity_id TEXT NOT NULL,
    conversation_id VARCHAR(255) NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    actor_user_id VARCHAR(255) NOT NULL,
    worker_request_id TEXT NOT NULL,
    tool_call_id TEXT NOT NULL,
    record_json JSONB NOT NULL,
    PRIMARY KEY (kind, activity_id),
    UNIQUE (worker_request_id, tool_call_id, kind)
);
CREATE INDEX background_activity_origins_conversation ON background_activity_origins(conversation_id);
