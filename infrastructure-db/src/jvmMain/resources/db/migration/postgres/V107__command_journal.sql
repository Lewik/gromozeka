CREATE TABLE command_journal_entries (
    id TEXT PRIMARY KEY,
    origin TEXT NOT NULL CHECK (origin IN (
        'CONVERSATION_TOOL_CALL', 'DIRECT_MCP', 'VISUAL_HANDLER', 'REPORTED_BY_CALLER'
    )),
    actor_user_id TEXT NOT NULL,
    project_id TEXT NOT NULL,
    conversation_id TEXT NOT NULL,
    thread_id TEXT NOT NULL,
    agent_definition_id TEXT,
    source_runtime_task_id TEXT,
    source_tool_call_id TEXT,
    parent_entry_id TEXT REFERENCES command_journal_entries(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (conversation_id, source_tool_call_id),
    CHECK (source_tool_call_id IS NOT NULL OR origin = 'VISUAL_HANDLER')
);

CREATE TABLE command_journal_calls (
    id TEXT PRIMARY KEY,
    entry_id TEXT NOT NULL REFERENCES command_journal_entries(id) ON DELETE CASCADE,
    parent_id TEXT REFERENCES command_journal_calls(id) ON DELETE SET NULL,
    source_tool_call_id TEXT,
    kind TEXT NOT NULL CHECK (kind IN ('COMMAND', 'PYTHON', 'MONITOR', 'INPUT', 'TOOL')),
    tool_name TEXT NOT NULL,
    worker_id TEXT,
    workspace_id TEXT,
    workspace_mount_id TEXT,
    workspace_root_path TEXT,
    working_directory TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (entry_id, source_tool_call_id),
    CHECK (parent_id IS NULL OR parent_id <> id),
    CHECK (workspace_mount_id IS NULL OR worker_id IS NOT NULL),
    CHECK (workspace_root_path IS NULL OR workspace_mount_id IS NOT NULL)
);

CREATE TABLE command_journal_events (
    id TEXT PRIMARY KEY,
    entry_id TEXT NOT NULL REFERENCES command_journal_entries(id) ON DELETE CASCADE,
    call_id TEXT NOT NULL REFERENCES command_journal_calls(id) ON DELETE CASCADE,
    source_key TEXT NOT NULL,
    kind TEXT NOT NULL CHECK (kind IN (
        'ACCEPTED', 'DISPATCHED', 'STARTED', 'COMPLETED', 'FAILED', 'CANCELLED', 'OUTCOME_UNKNOWN', 'REPORTED'
    )),
    occurred_at TIMESTAMPTZ NOT NULL,
    command_task_id TEXT,
    status_message TEXT,
    exit_code INTEGER,
    UNIQUE (entry_id, source_key)
);

CREATE TABLE command_journal_contents (
    id TEXT PRIMARY KEY CHECK (id ~ '^[0-9a-f]{64}$'),
    text_content TEXT NOT NULL,
    size_bytes INTEGER NOT NULL CHECK (size_bytes > 0),
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE command_journal_content_links (
    id BIGSERIAL PRIMARY KEY,
    entry_id TEXT NOT NULL REFERENCES command_journal_entries(id) ON DELETE CASCADE,
    call_id TEXT NOT NULL REFERENCES command_journal_calls(id) ON DELETE CASCADE,
    event_id TEXT REFERENCES command_journal_events(id) ON DELETE CASCADE,
    content_id TEXT NOT NULL REFERENCES command_journal_contents(id),
    kind TEXT NOT NULL CHECK (kind IN (
        'ARGUMENTS', 'COMMAND', 'PYTHON_SOURCE', 'OUTPUT', 'STDERR', 'REPORTED_OUTPUT'
    )),
    sequence INTEGER NOT NULL DEFAULT 0 CHECK (sequence >= 0),
    start_byte BIGINT,
    end_byte BIGINT,
    total_bytes BIGINT,
    truncated_before BOOLEAN NOT NULL DEFAULT FALSE,
    truncated_after BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL,
    CHECK ((start_byte IS NULL) = (end_byte IS NULL)),
    CHECK (start_byte IS NULL OR (start_byte >= 0 AND end_byte >= start_byte)),
    CHECK (total_bytes IS NULL OR end_byte IS NULL OR total_bytes >= end_byte)
);

CREATE UNIQUE INDEX command_journal_content_links_identity
    ON command_journal_content_links(call_id, COALESCE(event_id, ''), kind, sequence);
CREATE INDEX command_journal_entries_conversation
    ON command_journal_entries(conversation_id, created_at DESC, id DESC);
CREATE INDEX command_journal_entries_project
    ON command_journal_entries(project_id, created_at DESC, id DESC);
CREATE INDEX command_journal_entries_actor
    ON command_journal_entries(actor_user_id, created_at DESC, id DESC);
CREATE INDEX command_journal_entries_created
    ON command_journal_entries(created_at DESC, id DESC);
CREATE INDEX command_journal_entries_parent
    ON command_journal_entries(parent_entry_id) WHERE parent_entry_id IS NOT NULL;
CREATE INDEX command_journal_calls_entry
    ON command_journal_calls(entry_id, created_at, id);
CREATE INDEX command_journal_calls_worker
    ON command_journal_calls(worker_id, created_at DESC, id DESC) WHERE worker_id IS NOT NULL;
CREATE INDEX command_journal_events_entry
    ON command_journal_events(entry_id, occurred_at, id);
CREATE INDEX command_journal_events_task
    ON command_journal_events(command_task_id) WHERE command_task_id IS NOT NULL;
CREATE INDEX command_journal_content_links_entry
    ON command_journal_content_links(entry_id, call_id, id);
