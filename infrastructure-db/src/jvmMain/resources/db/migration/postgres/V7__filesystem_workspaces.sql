ALTER TABLE projects DROP COLUMN path;

CREATE TABLE workspaces (
    id VARCHAR(255) PRIMARY KEY,
    project_id VARCHAR(255) NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    kind VARCHAR(50) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_workspaces_project ON workspaces(project_id);

CREATE TABLE workspace_mounts (
    workspace_id VARCHAR(255) NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    worker_id VARCHAR(255) NOT NULL,
    root_path VARCHAR(1000) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (workspace_id, worker_id),
    UNIQUE (worker_id, root_path)
);

CREATE INDEX idx_workspace_mounts_worker ON workspace_mounts(worker_id);

ALTER TABLE conversations
    ADD COLUMN workspace_id VARCHAR(255) REFERENCES workspaces(id) ON DELETE RESTRICT;

ALTER TABLE conversations
    ALTER COLUMN workspace_id SET NOT NULL;

CREATE INDEX idx_conversations_workspace ON conversations(workspace_id);
