CREATE TABLE telegram_connections (
    id VARCHAR(255) PRIMARY KEY,
    revision BIGINT NOT NULL,
    payload JSONB NOT NULL
);

ALTER TABLE conversations ADD COLUMN external_channel TEXT;

CREATE TABLE telegram_conversation_bindings (
    conversation_id VARCHAR(255) PRIMARY KEY REFERENCES conversations(id) ON DELETE RESTRICT,
    connection_id VARCHAR(255) NOT NULL REFERENCES telegram_connections(id) ON DELETE RESTRICT,
    chat_id BIGINT NOT NULL,
    topic_id BIGINT NOT NULL DEFAULT 0,
    UNIQUE(connection_id, chat_id, topic_id)
);
