-- Persist the current names while Kotlin serializers continue to accept the old wire values.
UPDATE ai_model_configurations
SET payload_json = CASE
    WHEN (payload_json::jsonb -> 'compaction') ? 'compactionModelConfigurationId'
        THEN (payload_json::jsonb #- '{compaction,summaryModelConfigurationId}')::text
    ELSE jsonb_set(
        payload_json::jsonb #- '{compaction,summaryModelConfigurationId}',
        '{compaction,compactionModelConfigurationId}',
        payload_json::jsonb #> '{compaction,summaryModelConfigurationId}',
        true
    )::text
END
WHERE (payload_json::jsonb -> 'compaction') ? 'summaryModelConfigurationId';

UPDATE runtime_catalog_configuration SET revision = revision + 1;

UPDATE conversation_runtime_records
SET scheduling = replace(scheduling::text, '"strategy": "SUMMARIZE"', '"strategy": "COMPACT"')::jsonb
WHERE scheduling::text LIKE '%"strategy": "SUMMARIZE"%';

UPDATE conversation_runtime_events
SET entry_json = replace(entry_json::text, '"strategy": "SUMMARIZE"', '"strategy": "COMPACT"')::jsonb
WHERE entry_json::text LIKE '%"strategy": "SUMMARIZE"%';

UPDATE conversation_runtime_trace
SET entry_json = replace(entry_json::text, '"strategy": "SUMMARIZE"', '"strategy": "COMPACT"')::jsonb
WHERE entry_json::text LIKE '%"strategy": "SUMMARIZE"%';
