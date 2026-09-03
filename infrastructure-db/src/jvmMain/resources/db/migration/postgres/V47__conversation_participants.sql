CREATE TABLE conversation_user_participants (
    conversation_id VARCHAR(255) NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    user_id VARCHAR(255) NOT NULL REFERENCES users(id),
    PRIMARY KEY (conversation_id, user_id)
);

CREATE INDEX idx_conversation_user_participants_user
    ON conversation_user_participants(user_id, conversation_id);

CREATE TABLE conversation_agent_participants (
    conversation_id VARCHAR(255) NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    agent_definition_id VARCHAR(255) NOT NULL REFERENCES agents(id),
    PRIMARY KEY (conversation_id, agent_definition_id)
);

CREATE INDEX idx_conversation_agent_participants_agent
    ON conversation_agent_participants(agent_definition_id, conversation_id);

INSERT INTO conversation_agent_participants (conversation_id, agent_definition_id)
SELECT id, agent_definition_id
FROM conversations;

INSERT INTO conversation_user_participants (conversation_id, user_id)
SELECT conversation.id, membership.user_id
FROM conversations conversation
JOIN project_memberships membership ON membership.project_id = conversation.project_id;

DROP INDEX idx_conversations_agent_definition;

ALTER TABLE conversations
    DROP CONSTRAINT fk_conversations_agent_definition,
    DROP COLUMN agent_definition_id;
