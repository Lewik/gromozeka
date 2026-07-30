ALTER TABLE conversation_runtime_records
    ADD COLUMN ready_task_id TEXT,
    ADD COLUMN ready_at TIMESTAMPTZ;

UPDATE conversation_runtime_records
SET ready_task_id = CASE
        WHEN jsonb_typeof(record_json #> '{workOutbox,0,item,taskId}') = 'string'
            THEN record_json #>> '{workOutbox,0,item,taskId}'
        ELSE record_json #>> '{workOutbox,0,item,taskId,value}'
    END,
    ready_at = COALESCE(
        (record_json #>> '{workOutbox,0,item,createdAt}')::TIMESTAMPTZ,
        updated_at
    ),
    record_json = record_json - 'workOutbox' - 'workSequence'
WHERE jsonb_typeof(record_json -> 'workOutbox') = 'array'
  AND jsonb_array_length(record_json -> 'workOutbox') > 0
  AND (
      record_json -> 'state' IS NULL
      OR record_json -> 'state' = 'null'::jsonb
      OR (
          COALESCE(
              record_json #>> '{state,activeTaskId}',
              record_json #>> '{state,activeTaskId,value}'
          ) IS NULL
          AND record_json #>> '{state,controlState}' = 'RUNNING'
      )
  );

UPDATE conversation_runtime_records
SET record_json = record_json - 'workOutbox' - 'workSequence'
WHERE record_json ? 'workOutbox'
   OR record_json ? 'workSequence';

CREATE INDEX idx_conversation_runtime_ready_work
    ON conversation_runtime_records(ready_at, conversation_id)
    WHERE ready_task_id IS NOT NULL;
