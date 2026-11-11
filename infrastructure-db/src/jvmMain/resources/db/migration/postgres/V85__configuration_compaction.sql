-- Compaction is a workflow policy on a concrete model configuration, not model knowledge.
-- Existing policies intentionally reset to disabled; the user opts in per configuration.
UPDATE ai_model_specs
SET payload_json = (payload_json::jsonb #- '{limits,textGeneration,autoCompaction}')::text;

UPDATE ai_model_configurations
SET payload_json = jsonb_set(payload_json::jsonb, '{compaction}',
    '{"thresholdTokens":null,"summaryModelConfigurationId":null}'::jsonb, true)::text;

DELETE FROM ai_runtime_assignments WHERE purpose = 'MESSAGE_SQUASH';
UPDATE runtime_catalog_configuration SET revision = revision + 1;
