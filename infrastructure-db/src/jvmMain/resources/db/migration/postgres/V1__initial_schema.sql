CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public;
CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;

CREATE TABLE projects (
    id VARCHAR(255) PRIMARY KEY,
    path VARCHAR(500) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    description TEXT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    last_used_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_projects_last_used_at ON projects(last_used_at DESC);

CREATE TABLE contexts (
    id VARCHAR(255) PRIMARY KEY,
    project_id VARCHAR(255) NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    content TEXT NOT NULL,
    files_json TEXT NOT NULL,
    links_json TEXT NOT NULL,
    tags TEXT NOT NULL,
    extracted_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE agents (
    id VARCHAR(255) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    prompts_json TEXT NOT NULL,
    runtime_selection_json TEXT NOT NULL,
    runtime_overrides_json TEXT NOT NULL,
    tools_json TEXT NOT NULL,
    description TEXT NULL,
    type VARCHAR(50) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_agents_type ON agents(type);

CREATE TABLE prompts (
    id VARCHAR(255) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    content TEXT NOT NULL,
    source_type VARCHAR(50) NOT NULL,
    source_path TEXT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_prompts_source_type ON prompts(source_type);

CREATE TABLE conversations (
    id VARCHAR(255) PRIMARY KEY,
    project_id VARCHAR(255) NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    agent_definition_id VARCHAR(255) NOT NULL,
    display_name VARCHAR(255) NOT NULL DEFAULT '',
    current_thread_id VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_conversations_project ON conversations(project_id);
CREATE INDEX idx_conversations_updated ON conversations(updated_at DESC);
CREATE INDEX idx_conversations_current_thread ON conversations(current_thread_id);
CREATE INDEX idx_conversations_agent_definition ON conversations(agent_definition_id);

CREATE TABLE threads (
    id VARCHAR(255) PRIMARY KEY,
    conversation_id VARCHAR(255) NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    original_thread_id VARCHAR(255) NULL,
    last_turn_number INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_threads_conversation ON threads(conversation_id);
CREATE INDEX idx_threads_created ON threads(conversation_id, created_at DESC);
CREATE INDEX idx_threads_original ON threads(original_thread_id);

CREATE TABLE messages (
    id VARCHAR(255) PRIMARY KEY,
    conversation_id VARCHAR(255) NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    original_ids_json TEXT NOT NULL DEFAULT '[]',
    reply_to_id VARCHAR(255) NULL,
    squash_operation_id VARCHAR(255) NULL,
    role VARCHAR(50) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    message_json TEXT NOT NULL
);

CREATE INDEX idx_messages_conversation ON messages(conversation_id);
CREATE INDEX idx_messages_created ON messages(conversation_id, created_at);

CREATE TABLE thread_messages (
    thread_id VARCHAR(255) NOT NULL REFERENCES threads(id) ON DELETE CASCADE,
    message_id VARCHAR(255) NOT NULL REFERENCES messages(id),
    position INTEGER NOT NULL,
    PRIMARY KEY (thread_id, message_id)
);

CREATE INDEX idx_thread_messages_thread_position ON thread_messages(thread_id, position);
CREATE INDEX idx_thread_messages_message ON thread_messages(message_id);

CREATE TABLE squash_operations (
    id VARCHAR(255) PRIMARY KEY,
    conversation_id VARCHAR(255) NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    source_message_ids TEXT NOT NULL,
    result_message_id VARCHAR(255) NOT NULL REFERENCES messages(id),
    prompt TEXT NULL,
    model VARCHAR(255) NULL,
    performed_by_agent BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_squash_operations_conversation ON squash_operations(conversation_id);
CREATE INDEX idx_squash_operations_result_message ON squash_operations(result_message_id);

CREATE TABLE tool_executions (
    id VARCHAR(255) PRIMARY KEY,
    conversation_id VARCHAR(255) NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    message_id VARCHAR(255) NOT NULL,
    tool_name VARCHAR(100) NOT NULL,
    input TEXT NOT NULL,
    output TEXT NULL,
    executed_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ NULL,
    duration_ms BIGINT NULL,
    status VARCHAR(50) NOT NULL,
    error TEXT NULL
);

CREATE TABLE token_usage_statistics (
    id VARCHAR(255) PRIMARY KEY,
    thread_id VARCHAR(255) NOT NULL REFERENCES threads(id) ON DELETE CASCADE,
    last_message_id VARCHAR(255) NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    timestamp TIMESTAMPTZ NOT NULL,
    prompt_tokens INTEGER NOT NULL,
    completion_tokens INTEGER NOT NULL,
    cache_creation_tokens INTEGER NOT NULL DEFAULT 0,
    cache_read_tokens INTEGER NOT NULL DEFAULT 0,
    thinking_tokens INTEGER NOT NULL DEFAULT 0,
    provider VARCHAR(50) NOT NULL,
    model_id VARCHAR(100) NOT NULL
);

CREATE INDEX idx_token_usage_thread ON token_usage_statistics(thread_id, timestamp DESC);

CREATE TABLE embedding_cache (
    text_hash VARCHAR(64) NOT NULL,
    model VARCHAR(100) NOT NULL,
    embedding_vector TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (text_hash, model)
);

CREATE INDEX idx_embedding_cache_model ON embedding_cache(model);
CREATE INDEX idx_embedding_cache_created ON embedding_cache(created_at);

CREATE TABLE memory_predicate_definitions (
    id TEXT PRIMARY KEY,
    namespace TEXT NULL,
    payload JSONB NOT NULL,
    predicate TEXT NOT NULL,
    active BOOLEAN NOT NULL
);

CREATE INDEX idx_memory_predicates_namespace ON memory_predicate_definitions(namespace);
CREATE INDEX idx_memory_predicates_lookup ON memory_predicate_definitions(namespace, predicate, active);
CREATE INDEX idx_memory_predicates_payload ON memory_predicate_definitions USING GIN(payload);

CREATE TABLE memory_sources (
    id TEXT PRIMARY KEY,
    namespace TEXT NOT NULL,
    payload JSONB NOT NULL,
    content_hash TEXT NOT NULL,
    conversation_id TEXT NULL,
    search_text TEXT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_memory_sources_namespace ON memory_sources(namespace);
CREATE INDEX idx_memory_sources_conversation ON memory_sources(conversation_id);
CREATE INDEX idx_memory_sources_content_hash ON memory_sources(content_hash);
CREATE INDEX idx_memory_sources_search_text_trgm ON memory_sources USING GIN(search_text gin_trgm_ops);
CREATE INDEX idx_memory_sources_payload ON memory_sources USING GIN(payload);

CREATE TABLE memory_runs (
    id TEXT PRIMARY KEY,
    namespace TEXT NOT NULL,
    payload JSONB NOT NULL,
    parent_run_id TEXT NULL,
    status TEXT NOT NULL,
    run_type TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_memory_runs_namespace ON memory_runs(namespace);
CREATE INDEX idx_memory_runs_parent_run ON memory_runs(parent_run_id);
CREATE INDEX idx_memory_runs_status_type ON memory_runs(namespace, status, run_type);
CREATE INDEX idx_memory_runs_payload ON memory_runs USING GIN(payload);

CREATE TABLE memory_entities (
    id TEXT PRIMARY KEY,
    namespace TEXT NOT NULL,
    payload JSONB NOT NULL,
    normalized_name TEXT NOT NULL,
    entity_type TEXT NOT NULL,
    status TEXT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_memory_entities_namespace ON memory_entities(namespace);
CREATE INDEX idx_memory_entities_lookup ON memory_entities(namespace, normalized_name, entity_type);
CREATE INDEX idx_memory_entities_status ON memory_entities(namespace, status);
CREATE INDEX idx_memory_entities_payload ON memory_entities USING GIN(payload);

CREATE TABLE memory_claims (
    id TEXT PRIMARY KEY,
    namespace TEXT NOT NULL,
    payload JSONB NOT NULL,
    subject_entity_id TEXT NOT NULL,
    object_entity_id TEXT NULL,
    predicate TEXT NOT NULL,
    status TEXT NOT NULL,
    scope_text TEXT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_memory_claims_namespace ON memory_claims(namespace);
CREATE INDEX idx_memory_claims_subject ON memory_claims(namespace, subject_entity_id);
CREATE INDEX idx_memory_claims_object ON memory_claims(namespace, object_entity_id);
CREATE INDEX idx_memory_claims_predicate_status ON memory_claims(namespace, predicate, status);
CREATE INDEX idx_memory_claims_payload ON memory_claims USING GIN(payload);

CREATE TABLE memory_notes (
    id TEXT PRIMARY KEY,
    namespace TEXT NOT NULL,
    payload JSONB NOT NULL,
    anchor_entity_id TEXT NULL,
    status TEXT NOT NULL,
    note_type TEXT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_memory_notes_namespace ON memory_notes(namespace);
CREATE INDEX idx_memory_notes_anchor ON memory_notes(namespace, anchor_entity_id);
CREATE INDEX idx_memory_notes_status_type ON memory_notes(namespace, status, note_type);
CREATE INDEX idx_memory_notes_payload ON memory_notes USING GIN(payload);

CREATE TABLE memory_action_items (
    id TEXT PRIMARY KEY,
    namespace TEXT NOT NULL,
    payload JSONB NOT NULL,
    owner_entity_id TEXT NULL,
    assignee_entity_id TEXT NULL,
    status TEXT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_memory_action_items_namespace ON memory_action_items(namespace);
CREATE INDEX idx_memory_action_items_owner ON memory_action_items(namespace, owner_entity_id);
CREATE INDEX idx_memory_action_items_assignee ON memory_action_items(namespace, assignee_entity_id);
CREATE INDEX idx_memory_action_items_status ON memory_action_items(namespace, status);
CREATE INDEX idx_memory_action_items_payload ON memory_action_items USING GIN(payload);

CREATE TABLE memory_profiles (
    id TEXT PRIMARY KEY,
    namespace TEXT NOT NULL,
    payload JSONB NOT NULL,
    owner_entity_id TEXT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_memory_profiles_namespace ON memory_profiles(namespace);
CREATE INDEX idx_memory_profiles_owner ON memory_profiles(namespace, owner_entity_id);
CREATE INDEX idx_memory_profiles_payload ON memory_profiles USING GIN(payload);

CREATE TABLE memory_episodes (
    id TEXT PRIMARY KEY,
    namespace TEXT NOT NULL,
    payload JSONB NOT NULL,
    owner_entity_id TEXT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_memory_episodes_namespace ON memory_episodes(namespace);
CREATE INDEX idx_memory_episodes_owner ON memory_episodes(namespace, owner_entity_id);
CREATE INDEX idx_memory_episodes_payload ON memory_episodes USING GIN(payload);
