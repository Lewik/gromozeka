CREATE TABLE user_notifications (
    id VARCHAR(128) PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    source_key VARCHAR(256),
    title TEXT NOT NULL,
    message TEXT NOT NULL,
    level TEXT NOT NULL CHECK (level IN ('INFO', 'WARNING', 'ERROR')),
    -- A navigation hint, not ownership: the inbox survives deletion of the source conversation.
    conversation_id VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL,
    read_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ,
    UNIQUE (user_id, source_key),
    CHECK (length(title) BETWEEN 1 AND 200),
    CHECK (octet_length(message) BETWEEN 1 AND 16384)
);
CREATE INDEX user_notifications_inbox ON user_notifications(user_id, created_at DESC, id DESC) WHERE deleted_at IS NULL;
CREATE INDEX user_notifications_unread ON user_notifications(user_id, created_at DESC, id DESC) WHERE read_at IS NULL AND deleted_at IS NULL;
