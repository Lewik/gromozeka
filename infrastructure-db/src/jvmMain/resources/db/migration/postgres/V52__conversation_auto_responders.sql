ALTER TABLE conversation_agent_participants
    ADD COLUMN auto_respond BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE conversation_agent_participants agent
SET auto_respond = TRUE
WHERE (SELECT COUNT(*) FROM conversation_user_participants users
       WHERE users.conversation_id = agent.conversation_id) = 1
  AND (SELECT COUNT(*) FROM conversation_agent_participants agents
       WHERE agents.conversation_id = agent.conversation_id) = 1;
