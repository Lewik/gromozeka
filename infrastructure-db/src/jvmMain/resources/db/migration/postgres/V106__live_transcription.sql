UPDATE ai_model_specs
SET payload_json = jsonb_set(
    payload_json::jsonb,
    '{capabilities}',
    COALESCE(payload_json::jsonb -> 'capabilities', '[]'::jsonb) || '"REALTIME_TRANSCRIPTION"'::jsonb,
    true
)::text
WHERE provider = 'OPENAI'
  AND model_id = 'gpt-transcribe'
  AND NOT (COALESCE(payload_json::jsonb -> 'capabilities', '[]'::jsonb) ? 'REALTIME_TRANSCRIPTION');

DELETE FROM ai_runtime_assignments
WHERE purpose IN ('LIVE_TRANSCRIPT_STABILIZER', 'LIVE_TRANSLATION');

UPDATE runtime_catalog_configuration SET revision = revision + 1;
