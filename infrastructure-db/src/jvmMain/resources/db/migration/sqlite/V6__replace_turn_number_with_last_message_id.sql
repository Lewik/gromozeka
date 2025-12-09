-- Replace turnNumber with lastMessageId in token_usage_statistics
-- 
-- Old design: Used turnNumber counter which was fragile across thread edits
-- New design: Use threadId + lastMessageId to snapshot conversation state
-- 
-- This is more reliable because:
-- 1. Threads are immutable (edits create new threads)
-- 2. threadId + lastMessageId uniquely identifies conversation snapshot
-- 3. All messages before lastMessageId are guaranteed unchanged
-- 4. Works correctly with thread branching (edit/delete/squash operations)

-- SQLite doesn't support ADD COLUMN with constraints or MODIFY in ALTER TABLE
-- So we need to recreate the table

-- Step 1: Create new table with correct schema
CREATE TABLE token_usage_statistics_new (
    id VARCHAR(255) PRIMARY KEY,
    thread_id VARCHAR(255) NOT NULL,
    last_message_id VARCHAR(255) NOT NULL,
    timestamp DATETIME NOT NULL,
    prompt_tokens INTEGER NOT NULL,
    completion_tokens INTEGER NOT NULL,
    cache_creation_tokens INTEGER NOT NULL DEFAULT 0,
    cache_read_tokens INTEGER NOT NULL DEFAULT 0,
    thinking_tokens INTEGER NOT NULL DEFAULT 0,
    provider VARCHAR(50) NOT NULL,
    model_id VARCHAR(100) NOT NULL,
    FOREIGN KEY (thread_id) REFERENCES threads(id) ON DELETE CASCADE,
    FOREIGN KEY (last_message_id) REFERENCES messages(id) ON DELETE CASCADE
);

-- Step 2: Migrate data from old table
-- For each usage record, find the last message in the thread
INSERT INTO token_usage_statistics_new (
    id, thread_id, last_message_id, timestamp,
    prompt_tokens, completion_tokens,
    cache_creation_tokens, cache_read_tokens, thinking_tokens,
    provider, model_id
)
SELECT
    tus.id,
    tus.thread_id,
    COALESCE(
        (SELECT tm.message_id
         FROM thread_messages tm
         WHERE tm.thread_id = tus.thread_id
         ORDER BY tm.position DESC
         LIMIT 1),
        'unknown'  -- Fallback for edge cases
    ) as last_message_id,
    tus.timestamp,
    tus.prompt_tokens,
    tus.completion_tokens,
    tus.cache_creation_tokens,
    tus.cache_read_tokens,
    tus.thinking_tokens,
    tus.provider,
    tus.model_id
FROM token_usage_statistics tus;

-- Step 3: Drop old table
DROP TABLE token_usage_statistics;

-- Step 4: Rename new table to original name
ALTER TABLE token_usage_statistics_new RENAME TO token_usage_statistics;
