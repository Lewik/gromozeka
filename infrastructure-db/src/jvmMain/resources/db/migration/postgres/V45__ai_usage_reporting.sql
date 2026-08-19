CREATE TABLE ai_usage_records (
    id VARCHAR(255) PRIMARY KEY,
    user_id VARCHAR(255) NULL,
    project_id VARCHAR(255) NULL,
    agent_definition_id VARCHAR(255) NULL,
    conversation_id VARCHAR(255) NULL,
    thread_id VARCHAR(255) NULL,
    last_message_id VARCHAR(255) NULL,
    timestamp TIMESTAMPTZ NOT NULL,
    runtime_purpose VARCHAR(128) NOT NULL,
    execution_target VARCHAR(255) NOT NULL,
    connection_kind VARCHAR(64) NOT NULL,
    connection_id VARCHAR(255) NOT NULL,
    model_configuration_id VARCHAR(255) NOT NULL,
    provider VARCHAR(50) NOT NULL,
    model_id VARCHAR(255) NOT NULL,
    prompt_tokens INTEGER NOT NULL,
    completion_tokens INTEGER NOT NULL,
    cache_creation_tokens INTEGER NOT NULL DEFAULT 0,
    cache_read_tokens INTEGER NOT NULL DEFAULT 0,
    thinking_tokens INTEGER NOT NULL DEFAULT 0,
    context_input_tokens INTEGER NULL,
    pricing_catalog_version VARCHAR(128) NULL,
    pricing_effective_at TIMESTAMPTZ NULL,
    input_nano_usd_per_million BIGINT NULL,
    cache_creation_nano_usd_per_million BIGINT NULL,
    cache_read_nano_usd_per_million BIGINT NULL,
    output_nano_usd_per_million BIGINT NULL,
    estimated_cost_nano_usd BIGINT NULL
);

INSERT INTO ai_usage_records (
    id,
    project_id,
    agent_definition_id,
    conversation_id,
    thread_id,
    last_message_id,
    timestamp,
    runtime_purpose,
    execution_target,
    connection_kind,
    connection_id,
    model_configuration_id,
    provider,
    model_id,
    prompt_tokens,
    completion_tokens,
    cache_creation_tokens,
    cache_read_tokens,
    thinking_tokens,
    context_input_tokens
)
SELECT
    usage.id,
    conversation.project_id,
    conversation.agent_definition_id,
    thread.conversation_id,
    usage.thread_id,
    usage.last_message_id,
    usage.timestamp,
    'CONVERSATION',
    'SERVER',
    usage.provider,
    'legacy',
    usage.model_id,
    usage.provider,
    usage.model_id,
    usage.prompt_tokens,
    usage.completion_tokens,
    usage.cache_creation_tokens,
    usage.cache_read_tokens,
    usage.thinking_tokens,
    NULL
FROM token_usage_statistics usage
JOIN threads thread ON thread.id = usage.thread_id
JOIN conversations conversation ON conversation.id = thread.conversation_id;

CREATE INDEX idx_ai_usage_timestamp ON ai_usage_records(timestamp DESC);
CREATE INDEX idx_ai_usage_thread ON ai_usage_records(thread_id, timestamp DESC);
CREATE INDEX idx_ai_usage_provider_model ON ai_usage_records(provider, model_id, timestamp DESC);
CREATE INDEX idx_ai_usage_project ON ai_usage_records(project_id, timestamp DESC);
CREATE INDEX idx_ai_usage_agent ON ai_usage_records(agent_definition_id, timestamp DESC);
CREATE INDEX idx_ai_usage_conversation ON ai_usage_records(conversation_id, timestamp DESC);
CREATE INDEX idx_ai_usage_purpose ON ai_usage_records(runtime_purpose, timestamp DESC);
