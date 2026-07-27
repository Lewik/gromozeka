DELETE FROM conversation_tab_layouts;
DELETE FROM conversations;
DELETE FROM agents;
DELETE FROM prompts;

ALTER TABLE agents
    ALTER COLUMN project_id DROP NOT NULL;

ALTER TABLE prompts
    ALTER COLUMN project_id DROP NOT NULL;

ALTER TABLE prompts
    RENAME COLUMN source_type TO scope;

ALTER TABLE prompts
    DROP COLUMN source_path;

ALTER INDEX idx_prompts_source_type
    RENAME TO idx_prompts_scope;

ALTER TABLE conversations
    ADD CONSTRAINT fk_conversations_agent_definition
        FOREIGN KEY (agent_definition_id) REFERENCES agents(id);

CREATE TABLE ai_connections (
    id VARCHAR(255) PRIMARY KEY,
    payload_json TEXT NOT NULL
);

CREATE TABLE ai_model_specs (
    provider VARCHAR(50) NOT NULL,
    model_id VARCHAR(255) NOT NULL,
    payload_json TEXT NOT NULL,
    PRIMARY KEY (provider, model_id)
);

CREATE TABLE ai_model_configurations (
    id VARCHAR(255) PRIMARY KEY,
    connection_id VARCHAR(255) NOT NULL REFERENCES ai_connections(id),
    payload_json TEXT NOT NULL
);

CREATE INDEX idx_ai_model_configurations_connection
    ON ai_model_configurations(connection_id);

CREATE TABLE ai_runtime_assignments (
    purpose VARCHAR(100) PRIMARY KEY,
    model_configuration_id VARCHAR(255) NOT NULL REFERENCES ai_model_configurations(id),
    payload_json TEXT NOT NULL
);

CREATE INDEX idx_ai_runtime_assignments_model
    ON ai_runtime_assignments(model_configuration_id);

CREATE TABLE runtime_catalog_configuration (
    id VARCHAR(32) PRIMARY KEY,
    default_agent_id VARCHAR(255) NOT NULL REFERENCES agents(id),
    revision BIGINT NOT NULL
);
