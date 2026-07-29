UPDATE conversation_runtime_records
SET record_json = record_json || jsonb_build_object(
    'revision', COALESCE((record_json ->> 'revision')::bigint, 0) + 1,
    'state', NULL,
    'activeTask', NULL,
    'pendingTasks', jsonb_build_array(),
    'toolExecutions', jsonb_build_array(),
    'incidents', jsonb_build_array(),
    'trace', jsonb_build_array(),
    'eventLog', jsonb_build_array(),
    'workOutbox', jsonb_build_array(),
    'completedIdempotencyKeys', jsonb_build_array()
),
updated_at = now();

TRUNCATE TABLE conversation_runtime_workers;
