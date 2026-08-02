UPDATE conversation_runtime_records
SET record_json = (
        record_json
            - 'state'
            - 'activeTask'
            - 'activeInsertions'
            - 'continuationTask'
            - 'pendingTasks'
            - 'incidents'
            - 'completedIdempotencyKeys'
    ) || jsonb_build_object(
        'revision', COALESCE((record_json ->> 'revision')::bigint, 0) + 1,
        'scheduling', jsonb_build_object(
            'conversationId', record_json -> 'conversationId',
            'executionState', 'null'::jsonb,
            'activeTask', 'null'::jsonb,
            'activeInsertions', '[]'::jsonb,
            'continuationTask', 'null'::jsonb,
            'pendingTasks', '[]'::jsonb,
            'incidents', '[]'::jsonb,
            'completedIdempotencyKeys', '[]'::jsonb
        ),
        'toolExecutions', '[]'::jsonb,
        'eventLog', '[]'::jsonb
    ),
    ready_task_id = NULL,
    ready_at = NULL,
    updated_at = CURRENT_TIMESTAMP;
