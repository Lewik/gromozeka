-- Migrate existing CLAUDE_CODE conversations to OLLAMA
-- Update conversations that used CLAUDE_CODE provider to use OLLAMA instead

UPDATE conversations
SET ai_provider = 'OLLAMA',
    model_name = 'llama3.2'
WHERE ai_provider = 'CLAUDE_CODE';
