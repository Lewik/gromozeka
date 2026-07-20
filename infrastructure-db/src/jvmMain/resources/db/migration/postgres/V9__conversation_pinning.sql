ALTER TABLE conversations
    ADD COLUMN pinned_at TIMESTAMPTZ NULL;

CREATE INDEX idx_conversations_pinned
    ON conversations(pinned_at DESC)
    WHERE pinned_at IS NOT NULL;
