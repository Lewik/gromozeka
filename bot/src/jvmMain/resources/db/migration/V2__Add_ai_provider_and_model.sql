-- Add AI provider and model selection to conversations
-- Per-conversation model selection for flexible AI integration

ALTER TABLE conversations ADD COLUMN ai_provider VARCHAR(50) NOT NULL DEFAULT 'CLAUDE_CODE';
ALTER TABLE conversations ADD COLUMN model_name VARCHAR(100) NOT NULL DEFAULT 'sonnet';

CREATE INDEX idx_conversations_ai_provider ON conversations(ai_provider);
