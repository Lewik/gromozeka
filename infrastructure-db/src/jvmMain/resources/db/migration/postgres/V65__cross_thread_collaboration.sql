-- Experimental collaboration state is independent of history compaction and runtime journals.
CREATE TABLE agent_requests (
    id TEXT PRIMARY KEY,
    source_conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    target_conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    revision BIGINT NOT NULL,
    state TEXT NOT NULL,
    record_json JSONB NOT NULL
);
CREATE INDEX agent_requests_source ON agent_requests(source_conversation_id);
CREATE INDEX agent_requests_target ON agent_requests(target_conversation_id);

CREATE TABLE agent_deliveries (
    id TEXT PRIMARY KEY,
    request_id TEXT REFERENCES agent_requests(id) ON DELETE CASCADE,
    target_conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    state TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    record_json JSONB NOT NULL
);
CREATE INDEX agent_deliveries_pending ON agent_deliveries(created_at, id) WHERE state = 'PENDING';

CREATE TABLE agent_response_drafts (
    id TEXT PRIMARY KEY,
    conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    record_json JSONB NOT NULL
);
CREATE INDEX agent_response_drafts_conversation ON agent_response_drafts(conversation_id);
