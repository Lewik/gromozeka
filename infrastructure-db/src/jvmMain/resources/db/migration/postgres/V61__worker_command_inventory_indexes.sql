-- Index only Worker identifiers, not command text, outputs, or the entire runtime JSON.
-- Keep these expressions in sync with workerCommandInventorySql.
CREATE INDEX idx_conversation_runtime_command_tasks_workers
    ON conversation_runtime_records
    USING GIN ((jsonb_path_query_array(record_json, '$.commandTasks[*].workerId')) jsonb_path_ops);

CREATE INDEX idx_conversation_runtime_command_monitors_workers
    ON conversation_runtime_records
    USING GIN ((jsonb_path_query_array(record_json, '$.commandMonitors[*].workerId')) jsonb_path_ops);
