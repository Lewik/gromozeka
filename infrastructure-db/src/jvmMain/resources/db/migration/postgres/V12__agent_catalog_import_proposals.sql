CREATE TABLE agent_catalog_import_proposals (
    workspace_id VARCHAR(255) NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    worker_id VARCHAR(255) NOT NULL,
    project_id VARCHAR(255) NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    workspace_name VARCHAR(255) NOT NULL,
    catalog_hash VARCHAR(64) NOT NULL,
    prompts_json TEXT NOT NULL,
    agents_json TEXT NOT NULL,
    validation_error TEXT NULL,
    status VARCHAR(50) NOT NULL,
    detected_at TIMESTAMPTZ NOT NULL,
    decided_at TIMESTAMPTZ NULL,
    PRIMARY KEY (workspace_id, worker_id)
);

CREATE INDEX idx_agent_catalog_import_proposals_project_status
    ON agent_catalog_import_proposals(project_id, status);
