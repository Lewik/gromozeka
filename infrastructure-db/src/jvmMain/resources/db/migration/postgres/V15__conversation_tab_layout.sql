ALTER TABLE conversations DROP COLUMN IF EXISTS pinned_at;

CREATE TABLE conversation_tab_layouts (
    id VARCHAR(64) PRIMARY KEY,
    conversation_ids_json TEXT NOT NULL,
    revision BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NULL
);
