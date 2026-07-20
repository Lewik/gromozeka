DELETE FROM conversations;
DELETE FROM agents;
DELETE FROM prompts;

ALTER TABLE agents
    ADD COLUMN project_id VARCHAR(255) NOT NULL REFERENCES projects(id) ON DELETE CASCADE;

ALTER TABLE prompts
    ADD COLUMN project_id VARCHAR(255) NOT NULL REFERENCES projects(id) ON DELETE CASCADE;

CREATE INDEX idx_agents_project ON agents(project_id);
CREATE INDEX idx_prompts_project ON prompts(project_id);
