-- Migrate persisted old names before application code and later migrations read the new schema.
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

UPDATE messages
SET message_json = replace(
    replace(message_json, '"strategy":"SUMMARIZE"', '"strategy":"COMPACT"'),
    '"id":"gromozeka.message-compaction.summarize"',
    '"id":"gromozeka.message-compaction.compact"'
)
WHERE message_json LIKE '%"strategy":"SUMMARIZE"%'
   OR message_json LIKE '%"id":"gromozeka.message-compaction.summarize"%';

UPDATE conversation_runtime_records
SET scheduling = replace(
    replace(scheduling::text, '"strategy": "SUMMARIZE"', '"strategy": "COMPACT"'),
    '"id": "gromozeka.message-compaction.summarize"',
    '"id": "gromozeka.message-compaction.compact"'
)::jsonb
WHERE scheduling::text LIKE '%"strategy": "SUMMARIZE"%'
   OR scheduling::text LIKE '%"id": "gromozeka.message-compaction.summarize"%';

UPDATE conversation_runtime_events
SET entry_json = replace(
    replace(entry_json::text, '"strategy": "SUMMARIZE"', '"strategy": "COMPACT"'),
    '"id": "gromozeka.message-compaction.summarize"',
    '"id": "gromozeka.message-compaction.compact"'
)::jsonb
WHERE entry_json::text LIKE '%"strategy": "SUMMARIZE"%'
   OR entry_json::text LIKE '%"id": "gromozeka.message-compaction.summarize"%';

UPDATE conversation_runtime_trace
SET entry_json = replace(
    replace(entry_json::text, '"strategy": "SUMMARIZE"', '"strategy": "COMPACT"'),
    '"id": "gromozeka.message-compaction.summarize"',
    '"id": "gromozeka.message-compaction.compact"'
)::jsonb
WHERE entry_json::text LIKE '%"strategy": "SUMMARIZE"%'
   OR entry_json::text LIKE '%"id": "gromozeka.message-compaction.summarize"%';
