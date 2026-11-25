-- PostgreSQL JSON operators cannot decode escaped NUL into text. Normalize only the
-- metadata projection input; the canonical message JSON remains byte-for-byte unchanged.
CREATE OR REPLACE FUNCTION gromozeka_message_context_metadata(document json)
RETURNS text
LANGUAGE plpgsql IMMUTABLE STRICT
AS $$
DECLARE
    metadata_document json := regexp_replace(
        document::text,
        '(^|[^\\])((?:\\\\)*)\\u0000',
        '\1\2�',
        'g'
    )::json;
BEGIN
    RETURN (WITH content AS (
        SELECT value FROM json_array_elements(COALESCE(metadata_document -> 'content', '[]'::json))
    ), instructions AS (
        SELECT value, ordinal
        FROM json_array_elements(COALESCE(metadata_document -> 'instructions', '[]'::json)) WITH ORDINALITY AS item(value, ordinal)
    ), original_content AS (
        SELECT value FROM json_array_elements(CASE
            WHEN metadata_document ->> 'role' = 'ASSISTANT' AND metadata_document #>> '{author,type}' = 'agent'
            THEN COALESCE(metadata_document #> '{providerMetadata,gromozekaCollaborationOriginalContent}', '[]'::json)
            ELSE '[]'::json END)
    )
    SELECT json_build_object(
        'hasCompaction',
            EXISTS (SELECT 1 FROM content WHERE value ->> 'type' = 'ContextCompactionResult') OR
            EXISTS (SELECT 1 FROM original_content WHERE value ->> 'type' = 'ContextCompactionResult'),
        'humanInput',
            COALESCE(metadata_document ->> 'role' = 'USER', false) AND
            EXISTS (SELECT 1 FROM content WHERE value ->> 'type' IN ('Message', 'visual_interaction')) AND
            NOT COALESCE(json_typeof(metadata_document #> '{providerMetadata,synthetic}') = 'boolean' AND
                metadata_document #>> '{providerMetadata,synthetic}' = 'true', false) AND
            NOT EXISTS (SELECT 1 FROM instructions WHERE value ->> 'type' = 'agent'),
        'userText',
            COALESCE(metadata_document ->> 'role' = 'USER', false) AND
            EXISTS (SELECT 1 FROM content WHERE value ->> 'type' = 'Message'),
        'userInstructionIds', COALESCE((
            SELECT json_agg(value -> 'id' ORDER BY ordinal) FROM instructions
            WHERE value ->> 'type' = 'com.gromozeka.domain.model.Conversation.Message.Instruction.UserInstruction'
        ), '[]'::json),
        'agentAuthored', COALESCE(metadata_document #>> '{author,type}' = 'agent', false),
        'nativeUsage', CASE WHEN json_typeof(metadata_document #> '{providerMetadata,provider}') = 'string' AND
            metadata_document #>> '{providerMetadata,provider}' = 'OPENAI_SUBSCRIPTION'
        THEN json_build_object(
            'connectionId', metadata_document #> '{providerMetadata,connectionId}',
            'model', metadata_document #> '{providerMetadata,model}',
            'tokens', metadata_document #> '{providerMetadata,openaiSubscriptionContextTokens}',
            'estimate', metadata_document #> '{providerMetadata,openaiSubscriptionContextEstimate}'
        ) ELSE NULL END
    )::text);
END
$$;

-- Only exceptional rows produced by the old function need to be regenerated.
UPDATE messages
SET message_json = message_json
WHERE context_metadata_json IS NULL;
