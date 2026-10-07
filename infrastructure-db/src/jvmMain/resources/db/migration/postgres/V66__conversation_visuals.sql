CREATE TABLE conversation_visuals (
    id TEXT PRIMARY KEY,
    conversation_id VARCHAR(255) NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    revision BIGINT NOT NULL CHECK (revision > 0),
    has_handler BOOLEAN NOT NULL,
    status TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    record_json JSONB NOT NULL
);
CREATE INDEX conversation_visuals_conversation ON conversation_visuals(conversation_id, created_at, id);
CREATE INDEX conversation_visuals_live_handlers ON conversation_visuals(id) WHERE has_handler AND status <> 'STOPPED';

CREATE TABLE visual_action_receipts (
    event_id TEXT PRIMARY KEY,
    visual_id TEXT NOT NULL REFERENCES conversation_visuals(id) ON DELETE CASCADE,
    record_json JSONB NOT NULL
);
CREATE INDEX visual_action_receipts_visual ON visual_action_receipts(visual_id);
