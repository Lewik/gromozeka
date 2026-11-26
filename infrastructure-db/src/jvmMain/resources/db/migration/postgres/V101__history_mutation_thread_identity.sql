DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM conversation_runtime_records r
        WHERE (
            r.scheduling #>> '{activeTask,payload,type}' = 'history_mutation'
            AND jsonb_typeof(r.scheduling #> '{activeTask,payload,expectedThreadId}') IS DISTINCT FROM 'string'
        ) OR (
            r.scheduling #>> '{continuationTask,payload,type}' = 'history_mutation'
            AND jsonb_typeof(r.scheduling #> '{continuationTask,payload,expectedThreadId}') IS DISTINCT FROM 'string'
        ) OR EXISTS (
            SELECT 1
            FROM jsonb_array_elements(COALESCE(r.scheduling -> 'activeInsertions', '[]'::jsonb)) task
            WHERE task #>> '{payload,type}' = 'history_mutation'
              AND jsonb_typeof(task #> '{payload,expectedThreadId}') IS DISTINCT FROM 'string'
        ) OR EXISTS (
            SELECT 1
            FROM jsonb_array_elements(COALESCE(r.scheduling -> 'pendingTasks', '[]'::jsonb)) task
            WHERE task #>> '{payload,type}' = 'history_mutation'
              AND jsonb_typeof(task #> '{payload,expectedThreadId}') IS DISTINCT FROM 'string'
        )
    ) THEN
        RAISE EXCEPTION 'Legacy executable history mutation has no expected thread; drain the runtime before upgrading';
    END IF;
END $$;

UPDATE conversation_runtime_records r
SET scheduling = jsonb_set(
    r.scheduling,
    '{incidents}',
    (
        SELECT COALESCE(jsonb_agg(incident.entry ORDER BY incident.position), '[]'::jsonb)
        FROM jsonb_array_elements(r.scheduling -> 'incidents') WITH ORDINALITY incident(entry, position)
        WHERE NOT (
            COALESCE(incident.entry #>> '{task,payload,type}', '') = 'history_mutation'
            AND jsonb_typeof(incident.entry #> '{task,payload,expectedThreadId}') IS DISTINCT FROM 'string'
        )
    )
)
WHERE jsonb_typeof(r.scheduling -> 'incidents') = 'array'
  AND EXISTS (
      SELECT 1
      FROM jsonb_array_elements(r.scheduling -> 'incidents') incident
      WHERE incident #>> '{task,payload,type}' = 'history_mutation'
        AND jsonb_typeof(incident #> '{task,payload,expectedThreadId}') IS DISTINCT FROM 'string'
  );
