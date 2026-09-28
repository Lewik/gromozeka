ALTER TABLE conversation_runtime_records
    ADD COLUMN revision BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN event_sequence BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN trace_sequence BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN scheduling JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN tool_executions JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN memory_operations JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN command_tasks JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN command_monitors JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN command_monitor_events JSONB NOT NULL DEFAULT '[]'::jsonb;

UPDATE conversation_runtime_records
SET revision = COALESCE((record_json ->> 'revision')::bigint, 0),
    event_sequence = COALESCE((record_json ->> 'eventSequence')::bigint, 0),
    trace_sequence = COALESCE((record_json ->> 'traceSequence')::bigint, 0),
    scheduling = COALESCE(record_json -> 'scheduling', jsonb_build_object('conversationId', conversation_id)),
    tool_executions = COALESCE(record_json -> 'toolExecutions', '[]'::jsonb),
    memory_operations = COALESCE(record_json -> 'memoryOperations', '[]'::jsonb),
    command_tasks = COALESCE(record_json -> 'commandTasks', '[]'::jsonb),
    command_monitors = COALESCE(record_json -> 'commandMonitors', '[]'::jsonb),
    command_monitor_events = COALESCE(record_json -> 'commandMonitorEvents', '[]'::jsonb);

CREATE TABLE conversation_runtime_events (
    conversation_id TEXT NOT NULL REFERENCES conversation_runtime_records(conversation_id) ON DELETE CASCADE,
    sequence BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    event_type TEXT NOT NULL,
    task_id TEXT,
    turn_id TEXT,
    message_id TEXT,
    entry_json JSONB NOT NULL,
    PRIMARY KEY (conversation_id, sequence)
);

CREATE TABLE conversation_runtime_trace (
    conversation_id TEXT NOT NULL REFERENCES conversation_runtime_records(conversation_id) ON DELETE CASCADE,
    sequence BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    entry_json JSONB NOT NULL,
    PRIMARY KEY (conversation_id, sequence)
);

INSERT INTO conversation_runtime_events
    (conversation_id, sequence, created_at, event_type, task_id, turn_id, message_id, entry_json)
SELECT conversation_id, (entry ->> 'sequence')::bigint, (entry ->> 'createdAt')::timestamptz,
       entry #>> '{event,type}', entry #>> '{event,taskId}', entry #>> '{event,turnId}',
       entry #>> '{event,message,id}', entry
FROM conversation_runtime_records,
     LATERAL jsonb_array_elements(COALESCE(record_json -> 'eventLog', '[]'::jsonb)) entry;

INSERT INTO conversation_runtime_trace (conversation_id, sequence, created_at, entry_json)
SELECT conversation_id, (entry ->> 'sequence')::bigint, (entry ->> 'createdAt')::timestamptz, entry
FROM conversation_runtime_records,
     LATERAL jsonb_array_elements(COALESCE(record_json -> 'trace', '[]'::jsonb)) entry;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM conversation_runtime_records r
        WHERE (SELECT count(*) FROM conversation_runtime_events e WHERE e.conversation_id = r.conversation_id)
              <> jsonb_array_length(COALESCE(r.record_json -> 'eventLog', '[]'::jsonb))
           OR (SELECT count(*) FROM conversation_runtime_trace t WHERE t.conversation_id = r.conversation_id)
              <> jsonb_array_length(COALESCE(r.record_json -> 'trace', '[]'::jsonb))
           OR r.event_sequence < COALESCE((SELECT max(sequence) FROM conversation_runtime_events e WHERE e.conversation_id = r.conversation_id), 0)
           OR r.trace_sequence < COALESCE((SELECT max(sequence) FROM conversation_runtime_trace t WHERE t.conversation_id = r.conversation_id), 0)
    ) THEN
        RAISE EXCEPTION 'Runtime journal migration failed to preserve counters or entries';
    END IF;
END $$;

CREATE INDEX idx_conversation_runtime_events_task
    ON conversation_runtime_events (conversation_id, task_id, sequence DESC) WHERE task_id IS NOT NULL;
CREATE INDEX idx_conversation_runtime_events_turn_messages
    ON conversation_runtime_events (conversation_id, turn_id, sequence) WHERE message_id IS NOT NULL;

DROP INDEX idx_conversation_runtime_command_tasks_workers;
DROP INDEX idx_conversation_runtime_command_monitors_workers;
ALTER TABLE conversation_runtime_records DROP COLUMN record_json;
CREATE INDEX idx_conversation_runtime_command_tasks_workers
    ON conversation_runtime_records USING GIN ((jsonb_path_query_array(command_tasks, '$[*].workerId')) jsonb_path_ops);
CREATE INDEX idx_conversation_runtime_command_monitors_workers
    ON conversation_runtime_records USING GIN ((jsonb_path_query_array(command_monitors, '$[*].workerId')) jsonb_path_ops);
