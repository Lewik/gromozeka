ALTER TABLE workspace_mounts ADD COLUMN id VARCHAR(255);

UPDATE workspace_mounts
SET id = workspace_id || ':' || worker_id;

ALTER TABLE workspace_mounts ALTER COLUMN id SET NOT NULL;
ALTER TABLE workspace_mounts DROP CONSTRAINT workspace_mounts_pkey;
ALTER TABLE workspace_mounts ADD CONSTRAINT workspace_mounts_pkey PRIMARY KEY (id);
ALTER TABLE workspace_mounts
    ADD CONSTRAINT workspace_mounts_workspace_worker_key UNIQUE (workspace_id, worker_id);
