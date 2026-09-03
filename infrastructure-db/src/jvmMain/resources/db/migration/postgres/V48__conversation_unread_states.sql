CREATE TABLE conversation_unread_states (
    conversation_id VARCHAR(255) NOT NULL,
    user_id VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (conversation_id, user_id),
    CONSTRAINT fk_conversation_unread_states_participant
        FOREIGN KEY (conversation_id, user_id)
        REFERENCES conversation_user_participants(conversation_id, user_id)
        ON DELETE CASCADE
);

CREATE INDEX idx_conversation_unread_states_user
    ON conversation_unread_states(user_id, conversation_id);
