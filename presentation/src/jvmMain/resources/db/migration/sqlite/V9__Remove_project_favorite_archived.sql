-- Remove favorite and archived columns from projects table
-- These fields moved to .gromozeka/project.json (name, description, domain_patterns)
-- SQL stores only: id, path, timestamps

-- SQLite doesn't support DROP COLUMN directly, need to recreate table
CREATE TABLE projects_new (
    id VARCHAR(255) PRIMARY KEY,
    path VARCHAR(500) NOT NULL,
    created_at BIGINT NOT NULL,
    last_used_at BIGINT NOT NULL
);

-- Copy data (excluding name, description, favorite, archived)
INSERT INTO projects_new (id, path, created_at, last_used_at)
SELECT id, path, created_at, last_used_at FROM projects;

-- Drop old table
DROP TABLE projects;

-- Rename new table
ALTER TABLE projects_new RENAME TO projects;
