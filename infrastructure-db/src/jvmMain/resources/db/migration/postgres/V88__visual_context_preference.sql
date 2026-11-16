UPDATE conversation_visuals
SET record_json = jsonb_set(record_json, '{includeStateInContext}', 'true'::jsonb)
WHERE NOT (record_json ? 'includeStateInContext');

ALTER TABLE conversation_visuals
    ADD CONSTRAINT conversation_visuals_context_preference
    CHECK ((jsonb_typeof(record_json -> 'includeStateInContext') = 'boolean') IS TRUE);
