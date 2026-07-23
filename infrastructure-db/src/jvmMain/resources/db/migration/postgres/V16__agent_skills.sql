CREATE TABLE agent_skills (
    id VARCHAR(255) PRIMARY KEY,
    project_id VARCHAR(255) NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    name VARCHAR(64) NOT NULL,
    description TEXT NOT NULL,
    instructions TEXT NOT NULL,
    license TEXT NULL,
    compatibility TEXT NULL,
    metadata_json TEXT NOT NULL,
    allowed_tools TEXT NULL,
    content_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (project_id, name)
);

CREATE INDEX idx_agent_skills_project ON agent_skills(project_id);

CREATE TABLE agent_skill_files (
    skill_id VARCHAR(255) NOT NULL REFERENCES agent_skills(id) ON DELETE CASCADE,
    path VARCHAR(1000) NOT NULL,
    content BYTEA NOT NULL,
    PRIMARY KEY (skill_id, path)
);

ALTER TABLE agents
    ADD COLUMN skills_json TEXT NOT NULL DEFAULT '[]';
