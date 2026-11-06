-- Preserve the previously hard-coded request rules for existing native model specs.
-- Gateway/alias IDs and already configured behavior are deliberately left untouched.
WITH changed AS (
    UPDATE ai_model_specs
    SET payload_json = (
        payload_json::jsonb || jsonb_build_object(
            'behavior', CASE model_id
                WHEN 'claude-opus-5' THEN '{
                    "chatCompletionsTokenLimit": "MAX_TOKENS",
                    "chatCompletionsReasoning": "ANTHROPIC_THINKING",
                    "maximumDisabledReasoningEffort": "HIGH"
                }'::jsonb
                WHEN 'claude-opus-5-5' THEN '{
                    "chatCompletionsTokenLimit": "MAX_TOKENS",
                    "chatCompletionsReasoning": "ANTHROPIC_THINKING",
                    "defaultReasoningMode": "ADAPTIVE",
                    "supportsForcedToolChoice": false,
                    "anthropicThinkingPrefixDrop": true
                }'::jsonb
            END,
            'reasoning', COALESCE(NULLIF(payload_json::jsonb -> 'reasoning', 'null'::jsonb), '{}'::jsonb) ||
                jsonb_build_object('modes', CASE model_id
                    WHEN 'claude-opus-5' THEN '["DISABLED", "ADAPTIVE"]'::jsonb
                    WHEN 'claude-opus-5-5' THEN '["ADAPTIVE"]'::jsonb
                END)
        )
    )::text
    WHERE provider = 'ANTHROPIC'
        AND model_id IN ('claude-opus-5', 'claude-opus-5-5')
        AND NOT (payload_json::jsonb ? 'behavior')
    RETURNING 1
)
UPDATE runtime_catalog_configuration
SET revision = revision + 1
WHERE EXISTS (SELECT 1 FROM changed);
