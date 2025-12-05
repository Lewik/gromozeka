-- Add provider column to token_usage_statistics
-- Stores the AI provider name (ANTHROPIC, GEMINI, OPEN_AI, OLLAMA, CLAUDE_CODE)

ALTER TABLE token_usage_statistics ADD COLUMN provider VARCHAR(50) NOT NULL DEFAULT 'ANTHROPIC';
