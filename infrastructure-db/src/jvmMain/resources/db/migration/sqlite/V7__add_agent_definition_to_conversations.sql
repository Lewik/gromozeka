-- Add agent_definition_id to conversations table
-- Replace ai_provider and model_name with reference to AgentDefinition

-- Step 1: Create default agent definition for migration
-- This agent will be used for all existing conversations that have ai_provider/model_name
INSERT OR IGNORE INTO agents (id, name, prompts_json, description, type, created_at, updated_at)
VALUES (
    'migrated-default-agent',
    'Default Agent (Migrated)',
    '[]',
    'Default agent created during migration from ai_provider/model_name to AgentDefinition',
    'BUILTIN',
    0,
    0
);

-- Step 2: Recreate conversations table with agent_definition_id
-- SQLite doesn't support DROP COLUMN, so we recreate the table

CREATE TABLE conversations_new (
    id VARCHAR(255) PRIMARY KEY,
    project_id VARCHAR(255) NOT NULL,
    agent_definition_id VARCHAR(255) NOT NULL,
    display_name VARCHAR(255) NOT NULL DEFAULT '',
    current_thread_id VARCHAR(255) NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE,
    FOREIGN KEY (agent_definition_id) REFERENCES agents(id)
);

-- Step 3: Copy data from old table
-- All existing conversations will use the default migrated agent
INSERT INTO conversations_new (id, project_id, agent_definition_id, display_name, current_thread_id, created_at, updated_at)
SELECT 
    id,
    project_id,
    'migrated-default-agent' as agent_definition_id,
    display_name,
    current_thread_id,
    created_at,
    updated_at
FROM conversations;

-- Step 4: Drop old table and rename new one
DROP TABLE conversations;
ALTER TABLE conversations_new RENAME TO conversations;

-- Step 5: Recreate indexes
CREATE INDEX idx_conversations_project ON conversations(project_id);
CREATE INDEX idx_conversations_updated ON conversations(updated_at);
CREATE INDEX idx_conversations_current_thread ON conversations(current_thread_id);
CREATE INDEX idx_conversations_agent_definition ON conversations(agent_definition_id);
