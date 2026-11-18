ALTER TABLE async_tool_operations ADD COLUMN claim_generation BIGINT NOT NULL DEFAULT 0;

UPDATE async_tool_operations SET claim_generation = 1 WHERE claimed_at IS NOT NULL;

ALTER TABLE async_tool_operations
    ADD CONSTRAINT async_tool_claim_generation_nonnegative CHECK (claim_generation >= 0),
    ADD CONSTRAINT async_tool_claim_generation_owner CHECK ((claimed_at IS NULL) = (claim_generation = 0));

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM worker_requests child
        LEFT JOIN async_tool_operations parent ON parent.id = child.id
        WHERE child.async_tool_json IS NOT NULL AND (
            parent.id IS NULL OR parent.claim_generation = 0 OR
            child.async_tool_json ->> 'operationId' IS DISTINCT FROM parent.id OR
            child.async_tool_json ->> 'executorSessionId' IS DISTINCT FROM parent.executor_session_id
        )
    ) THEN
        RAISE EXCEPTION 'Async Worker child does not match its parent claim';
    END IF;
END $$;

UPDATE worker_requests child
SET async_tool_json = child.async_tool_json || jsonb_build_object('claimGeneration', parent.claim_generation)
FROM async_tool_operations parent
WHERE child.id = parent.id AND child.async_tool_json IS NOT NULL;
