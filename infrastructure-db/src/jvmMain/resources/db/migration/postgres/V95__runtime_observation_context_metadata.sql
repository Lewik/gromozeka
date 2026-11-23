-- Keep generated sparse-read metadata aligned with the canonical Kotlin projection.
-- Recompute only existing Runtime-observation/boundary rows; all other legacy rows
-- decode the new false-valued fields through Kotlin defaults.
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
    ), facts AS (
        SELECT
            COALESCE(document ->> 'role' = 'RUNTIME', false) AND
            COALESCE(document #>> '{author,type}' = 'runtime', false) AND
            EXISTS (SELECT 1 FROM instructions) AND
            NOT EXISTS (
                SELECT 1 FROM instructions
                WHERE value ->> 'type' IS DISTINCT FROM 'runtime_observation'
            ) AS runtime_observation
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
        'nativeUsage', CASE WHEN json_typeof(document #> '{providerMetadata,provider}') = 'string' AND
            document #>> '{providerMetadata,provider}' = 'OPENAI_SUBSCRIPTION'
        THEN json_build_object(
            'connectionId', document #> '{providerMetadata,connectionId}',
            'model', document #> '{providerMetadata,model}',
            'tokens', document #> '{providerMetadata,openaiSubscriptionContextTokens}',
            'estimate', document #> '{providerMetadata,openaiSubscriptionContextEstimate}'
        ) ELSE NULL END,
        'durableRuntimeObservationBoundary',
            COALESCE(json_typeof(document #> '{providerMetadata,gromozekaDurableRuntimeObservationBoundary}') = 'boolean' AND
                document #>> '{providerMetadata,gromozekaDurableRuntimeObservationBoundary}' = 'true', false) AND
            ((SELECT runtime_observation FROM facts) OR
                ((SELECT count(*) FROM content) = 1 AND
                    EXISTS (SELECT 1 FROM content WHERE value ->> 'type' = 'ContextCompactionResult'))),
        'runtimeObservation', (SELECT runtime_observation FROM facts),
        'keyedRuntimeObservation', (SELECT runtime_observation FROM facts) AND
            NOT EXISTS (SELECT 1 FROM instructions WHERE value ->> 'key' IS NULL)
    )::text);
EXCEPTION WHEN SQLSTATE '22P05' THEN
    RETURN NULL;
END
$$;

UPDATE messages
SET message_json = message_json
WHERE message_json LIKE '%"runtime_observation"%'
   OR message_json LIKE '%"gromozekaDurableRuntimeObservationBoundary"%';
