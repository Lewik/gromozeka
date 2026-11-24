-- Keep generated sparse-read metadata aligned with the canonical Kotlin projection.
-- Recompute existing agent-authored rows; other legacy rows
-- decode the new false-valued field through the Kotlin default.
CREATE OR REPLACE FUNCTION gromozeka_message_context_metadata(document json)
RETURNS text
LANGUAGE plpgsql IMMUTABLE STRICT
AS $$
BEGIN
    RETURN (WITH content AS (
        SELECT value FROM json_array_elements(COALESCE(document -> 'content', '[]'::json))
    ), instructions AS (
        SELECT value, ordinal
        FROM json_array_elements(COALESCE(document -> 'instructions', '[]'::json)) WITH ORDINALITY AS item(value, ordinal)
    ), original_content AS (
        SELECT value FROM json_array_elements(CASE
            WHEN document ->> 'role' = 'ASSISTANT' AND document #>> '{author,type}' = 'agent'
            THEN COALESCE(document #> '{providerMetadata,gromozekaCollaborationOriginalContent}', '[]'::json)
            ELSE '[]'::json END)
    )
    SELECT json_build_object(
        'hasCompaction',
            EXISTS (SELECT 1 FROM content WHERE value ->> 'type' = 'ContextCompactionResult') OR
            EXISTS (SELECT 1 FROM original_content WHERE value ->> 'type' = 'ContextCompactionResult'),
        'humanInput',
            COALESCE(document ->> 'role' = 'USER', false) AND
            EXISTS (SELECT 1 FROM content WHERE value ->> 'type' IN ('Message', 'visual_interaction')) AND
            NOT COALESCE(json_typeof(document #> '{providerMetadata,synthetic}') = 'boolean' AND
                document #>> '{providerMetadata,synthetic}' = 'true', false) AND
            NOT EXISTS (SELECT 1 FROM instructions WHERE value ->> 'type' = 'agent'),
        'userText',
            COALESCE(document ->> 'role' = 'USER', false) AND
            EXISTS (SELECT 1 FROM content WHERE value ->> 'type' = 'Message'),
        'userInstructionIds', COALESCE((
            SELECT json_agg(value -> 'id' ORDER BY ordinal) FROM instructions
            WHERE value ->> 'type' = 'com.gromozeka.domain.model.Conversation.Message.Instruction.UserInstruction'
        ), '[]'::json),
        'agentAuthored', COALESCE(document #>> '{author,type}' = 'agent', false),
        'nativeUsage', CASE WHEN json_typeof(document #> '{providerMetadata,provider}') = 'string' AND
            document #>> '{providerMetadata,provider}' = 'OPENAI_SUBSCRIPTION'
        THEN json_build_object(
            'connectionId', document #> '{providerMetadata,connectionId}',
            'model', document #> '{providerMetadata,model}',
            'tokens', document #> '{providerMetadata,openaiSubscriptionContextTokens}',
            'estimate', document #> '{providerMetadata,openaiSubscriptionContextEstimate}'
        ) ELSE NULL END
    )::text);
EXCEPTION WHEN SQLSTATE '22P05' THEN
    RETURN NULL;
END
$$;

UPDATE messages
SET message_json = message_json
WHERE message_json LIKE '%"type":"agent"%';
