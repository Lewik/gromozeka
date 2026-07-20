DROP INDEX IF EXISTS idx_conversations_workspace;

ALTER TABLE conversations DROP COLUMN workspace_id;
