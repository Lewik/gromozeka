ALTER TABLE artifacts ADD COLUMN search_text TEXT;

UPDATE conversation_runtime_records
SET record_json = jsonb_set(record_json, '{commandTasks}', (
    SELECT coalesce(jsonb_agg(
        item - 'terminalOutput'
            || CASE WHEN jsonb_typeof(item -> 'terminalOutput') = 'string'
                THEN jsonb_build_object('terminalOutputContent', jsonb_build_object('base64',
                    replace(encode(convert_to(item ->> 'terminalOutput', 'UTF8'), 'base64'), E'\n', '')))
                ELSE '{}'::jsonb END
        ORDER BY position
    ), '[]'::jsonb)
    FROM jsonb_array_elements(record_json -> 'commandTasks') WITH ORDINALITY AS entries(item, position)
))
WHERE jsonb_typeof(record_json -> 'commandTasks') = 'array';

UPDATE conversation_runtime_records
SET record_json = jsonb_set(record_json, '{commandMonitors}', (
    SELECT coalesce(jsonb_agg(
        item - 'terminalOutput' - 'terminalErrorOutput'
            || CASE WHEN jsonb_typeof(item -> 'terminalOutput') = 'string'
                THEN jsonb_build_object('terminalOutputContent', jsonb_build_object('base64',
                    replace(encode(convert_to(item ->> 'terminalOutput', 'UTF8'), 'base64'), E'\n', '')))
                ELSE '{}'::jsonb END
            || CASE WHEN jsonb_typeof(item -> 'terminalErrorOutput') = 'string'
                THEN jsonb_build_object('terminalErrorContent', jsonb_build_object('base64',
                    replace(encode(convert_to(item ->> 'terminalErrorOutput', 'UTF8'), 'base64'), E'\n', '')))
                ELSE '{}'::jsonb END
        ORDER BY position
    ), '[]'::jsonb)
    FROM jsonb_array_elements(record_json -> 'commandMonitors') WITH ORDINALITY AS entries(item, position)
))
WHERE jsonb_typeof(record_json -> 'commandMonitors') = 'array';

UPDATE conversation_runtime_records
SET record_json = jsonb_set(record_json, '{commandMonitorEvents}', (
    SELECT coalesce(jsonb_agg(
        item - 'output'
            || CASE WHEN jsonb_typeof(item -> 'output') = 'string'
                THEN jsonb_build_object('content', jsonb_build_object('base64',
                    replace(encode(convert_to(item ->> 'output', 'UTF8'), 'base64'), E'\n', '')))
                ELSE '{}'::jsonb END
        ORDER BY position
    ), '[]'::jsonb)
    FROM jsonb_array_elements(record_json -> 'commandMonitorEvents') WITH ORDINALITY AS entries(item, position)
))
WHERE jsonb_typeof(record_json -> 'commandMonitorEvents') = 'array';
