-- Migrate agents from system_prompt to prompts_json architecture
-- This migration creates a prompts table and converts existing system_prompt values to inline prompts

-- Step 1: Create prompts table for storing inline prompts
CREATE TABLE prompts (
    id VARCHAR(255) NOT NULL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    content TEXT NOT NULL,
    source_type VARCHAR(50) NOT NULL,  -- BUILTIN, USER_FILE, CLAUDE_GLOBAL, CLAUDE_PROJECT, IMPORTED, REMOTE_URL, INLINE
    source_path TEXT NULL,              -- Path for file-based prompts, URL for remote, null for inline
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
);

CREATE INDEX idx_prompts_source_type ON prompts(source_type);

-- Step 2: Migrate existing agents - create inline prompt for each agent's system_prompt
INSERT INTO prompts (id, name, content, source_type, source_path, created_at, updated_at)
SELECT 
    'inline:' || id as id,
    name || ' (migrated prompt)' as name,
    system_prompt as content,
    'INLINE' as source_type,
    NULL as source_path,
    created_at,
    updated_at
FROM agents;

-- Step 3: Recreate agents table with prompts_json instead of system_prompt
-- SQLite doesn't support DROP COLUMN, so we recreate the table

CREATE TABLE agents_new (
    id VARCHAR(255) NOT NULL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    prompts_json TEXT NOT NULL,
    description TEXT NULL,
    is_builtin BOOLEAN NOT NULL,
    usage_count INT NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
);

-- Step 4: Copy data with prompts_json populated
INSERT INTO agents_new (id, name, prompts_json, description, is_builtin, usage_count, created_at, updated_at)
SELECT 
    id,
    name,
    '["inline:' || id || '"]' as prompts_json,  -- Reference to the inline prompt we created
    description,
    is_builtin,
    usage_count,
    created_at,
    updated_at
FROM agents;

-- Step 5: Drop old table and rename new one
DROP TABLE agents;
ALTER TABLE agents_new RENAME TO agents;
