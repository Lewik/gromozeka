CREATE TABLE async_tool_operations (
    id TEXT PRIMARY KEY,
    actor_user_id TEXT NOT NULL,
    project_id TEXT NOT NULL,
    conversation_id TEXT NOT NULL,
    thread_id TEXT NOT NULL,
    agent_definition_id TEXT NOT NULL,
    source_task_id TEXT NOT NULL,
    source_call_id TEXT NOT NULL,
    worker_id TEXT,
    workspace_mount_id TEXT,
    slot_number BIGINT,
    slot_origin_json JSONB,
    invocation_ciphertext TEXT NOT NULL,
    invocation_nonce TEXT NOT NULL,
    invocation_version INTEGER NOT NULL,
    phase TEXT NOT NULL CHECK (phase IN ('ACCEPTED', 'ACKNOWLEDGED', 'CLAIMED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'OUTCOME_UNKNOWN')),
    created_at TIMESTAMPTZ NOT NULL,
    acknowledged_at TIMESTAMPTZ,
    claimed_at TIMESTAMPTZ,
    started_at TIMESTAMPTZ,
    executor_session_id TEXT,
    lease_deadline TIMESTAMPTZ,
    worker_request_created_at TIMESTAMPTZ,
    cancel_requested_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    outcome_ciphertext TEXT,
    outcome_nonce TEXT,
    outcome_version INTEGER,
    projected_at TIMESTAMPTZ,
    UNIQUE (conversation_id, source_task_id, source_call_id),
    CHECK ((slot_number IS NULL) = (slot_origin_json IS NULL)),
    CHECK ((completed_at IS NOT NULL) = (phase IN ('SUCCEEDED', 'FAILED', 'CANCELLED', 'OUTCOME_UNKNOWN'))),
    CHECK ((outcome_ciphertext IS NOT NULL) = (completed_at IS NOT NULL)),
    CHECK ((outcome_nonce IS NOT NULL) = (completed_at IS NOT NULL)),
    CHECK ((outcome_version IS NOT NULL) = (completed_at IS NOT NULL)),
    CHECK ((claimed_at IS NULL) = (executor_session_id IS NULL)),
    CHECK (started_at IS NULL OR claimed_at IS NOT NULL),
    CHECK (worker_request_created_at IS NULL OR (worker_id IS NOT NULL AND claimed_at IS NOT NULL AND acknowledged_at IS NOT NULL)),
    CHECK ((lease_deadline IS NOT NULL) = (phase IN ('CLAIMED', 'RUNNING'))),
    CHECK (phase NOT IN ('ACKNOWLEDGED', 'CLAIMED', 'RUNNING') OR acknowledged_at IS NOT NULL),
    CHECK (phase <> 'ACCEPTED' OR (acknowledged_at IS NULL AND claimed_at IS NULL AND started_at IS NULL)),
    CHECK (phase <> 'ACKNOWLEDGED' OR claimed_at IS NULL),
    CHECK (phase <> 'CLAIMED' OR (claimed_at IS NOT NULL AND started_at IS NULL)),
    CHECK (phase <> 'RUNNING' OR started_at IS NOT NULL),
    CHECK (phase <> 'CANCELLED' OR started_at IS NULL),
    CHECK (phase <> 'SUCCEEDED' OR (acknowledged_at IS NOT NULL AND started_at IS NOT NULL)),
    CHECK (phase <> 'OUTCOME_UNKNOWN' OR claimed_at IS NOT NULL),
    CHECK (projected_at IS NULL OR (completed_at IS NOT NULL AND acknowledged_at IS NOT NULL))
);

CREATE INDEX async_tool_operations_pending ON async_tool_operations (phase, created_at, id) WHERE completed_at IS NULL;
CREATE INDEX async_tool_operations_actor_pending ON async_tool_operations (actor_user_id) WHERE completed_at IS NULL;
CREATE INDEX async_tool_operations_expired ON async_tool_operations (lease_deadline, id) WHERE lease_deadline IS NOT NULL;
CREATE INDEX async_tool_operations_projection ON async_tool_operations (completed_at, id) WHERE completed_at IS NOT NULL AND acknowledged_at IS NOT NULL AND projected_at IS NULL;
CREATE INDEX async_tool_operations_slot ON async_tool_operations (slot_number) WHERE slot_number IS NOT NULL;

ALTER TABLE worker_requests ADD COLUMN async_tool_json JSONB;
