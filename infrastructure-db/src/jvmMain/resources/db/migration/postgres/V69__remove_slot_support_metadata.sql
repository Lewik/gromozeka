UPDATE conversation_runtime_workers
SET registration_json = jsonb_set(
    registration_json,
    '{tools}',
    (
        SELECT jsonb_agg(tool #- '{metadata,supportsSlotContext}' ORDER BY ordinal)
        FROM jsonb_array_elements(registration_json->'tools') WITH ORDINALITY AS entry(tool, ordinal)
    )
)
WHERE EXISTS (
    SELECT 1
    FROM jsonb_array_elements(registration_json->'tools') AS entry(tool)
    WHERE tool->'metadata' ? 'supportsSlotContext'
);

UPDATE ai_tool_contracts
SET payload_json = (payload_json::jsonb #- '{descriptor,metadata,supportsSlotContext}')::text
WHERE payload_json::jsonb->'descriptor'->'metadata' ? 'supportsSlotContext';
