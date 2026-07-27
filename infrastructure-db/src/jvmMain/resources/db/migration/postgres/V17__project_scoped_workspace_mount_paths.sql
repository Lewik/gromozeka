ALTER TABLE workspace_mounts
    ADD COLUMN project_id VARCHAR(255);

UPDATE workspace_mounts
SET project_id = workspaces.project_id
FROM workspaces
WHERE workspace_mounts.workspace_id = workspaces.id;

ALTER TABLE workspace_mounts
    ALTER COLUMN project_id SET NOT NULL;

ALTER TABLE workspaces
    ADD CONSTRAINT workspaces_id_project_id_key UNIQUE (id, project_id);

ALTER TABLE workspace_mounts
    DROP CONSTRAINT workspace_mounts_workspace_id_fkey;

ALTER TABLE workspace_mounts
    ADD CONSTRAINT workspace_mounts_workspace_project_fkey
        FOREIGN KEY (workspace_id, project_id)
        REFERENCES workspaces(id, project_id)
        ON DELETE CASCADE;

ALTER TABLE workspace_mounts
    DROP CONSTRAINT workspace_mounts_worker_id_root_path_key;

ALTER TABLE workspace_mounts
    ADD CONSTRAINT workspace_mounts_project_worker_root_path_key
        UNIQUE (project_id, worker_id, root_path);
