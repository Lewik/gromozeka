-- Agent Memory Blueprint v3
-- Baseline schema for Postgres + pgvector.
-- Adjust VECTOR(1536) to match your embedding model dimension.

CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE IF NOT EXISTS sources (
    source_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    namespace_id TEXT NOT NULL,
    source_type TEXT NOT NULL CHECK (source_type IN ('chat_turn', 'document_chunk', 'tool_output', 'imported_note', 'external_record')),
    conversation_id TEXT NULL,
    turn_id TEXT NULL,
    speaker_role TEXT NOT NULL CHECK (speaker_role IN ('user', 'assistant', 'tool', 'system', 'external')),
    author_label TEXT NULL,
    content_text TEXT NOT NULL,
    content_hash TEXT NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    retention_class TEXT NOT NULL DEFAULT 'standard' CHECK (retention_class IN ('standard', 'short_lived', 'sensitive', 'imported')),
    expires_at TIMESTAMPTZ NULL,
    deleted_at TIMESTAMPTZ NULL,
    embedding VECTOR(1536) NULL,
    source_tsv tsvector GENERATED ALWAYS AS (to_tsvector('simple', coalesce(content_text, ''))) STORED
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_sources_namespace_hash_observed
    ON sources(namespace_id, content_hash, observed_at);

CREATE INDEX IF NOT EXISTS ix_sources_namespace_observed_at
    ON sources(namespace_id, observed_at DESC);

CREATE INDEX IF NOT EXISTS ix_sources_namespace_retention
    ON sources(namespace_id, retention_class, expires_at, deleted_at);

CREATE INDEX IF NOT EXISTS ix_sources_namespace_conversation
    ON sources(namespace_id, conversation_id);

CREATE INDEX IF NOT EXISTS ix_sources_tsv
    ON sources USING GIN(source_tsv);

CREATE INDEX IF NOT EXISTS ix_sources_metadata_json
    ON sources USING GIN(metadata_json);

CREATE INDEX IF NOT EXISTS ix_sources_embedding_hnsw
    ON sources USING hnsw (embedding vector_cosine_ops);


CREATE TABLE IF NOT EXISTS memory_runs (
    run_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    namespace_id TEXT NOT NULL,
    run_type TEXT NOT NULL CHECK (
        run_type IN (
            'route',
            'retrieve_update',
            'canonicalize',
            'construct_notes',
            'reconcile_notes',
            'extract_claims',
            'reconcile_claims',
            'update_profile',
            'update_tasks',
            'consolidate_notes',
            'repair_memory',
            'apply_retention',
            'read_plan',
            'compose_answer',
            'compact'
        )
    ),
    trigger_mode TEXT NOT NULL CHECK (trigger_mode IN ('hot_path', 'background', 'manual', 'slow_path')),
    source_ids_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    retrieved_item_ids_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    retrieval_budget_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    prompt_name TEXT NOT NULL,
    prompt_version TEXT NOT NULL,
    model_name TEXT NOT NULL,
    input_hash TEXT NOT NULL,
    output_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    applied_ops_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    repair_actions_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    latency_ms INTEGER NOT NULL DEFAULT 0 CHECK (latency_ms >= 0),
    token_input INTEGER NULL CHECK (token_input IS NULL OR token_input >= 0),
    token_output INTEGER NULL CHECK (token_output IS NULL OR token_output >= 0),
    status TEXT NOT NULL CHECK (status IN ('success', 'failed', 'partial')),
    error_text TEXT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_memory_runs_namespace_created
    ON memory_runs(namespace_id, created_at DESC);

CREATE INDEX IF NOT EXISTS ix_memory_runs_prompt
    ON memory_runs(prompt_name, prompt_version);

CREATE UNIQUE INDEX IF NOT EXISTS ux_memory_runs_input_hash
    ON memory_runs(namespace_id, run_type, input_hash, prompt_version);


CREATE TABLE IF NOT EXISTS entities (
    entity_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    namespace_id TEXT NOT NULL,
    entity_type TEXT NOT NULL CHECK (
        entity_type IN (
            'person', 'agent', 'organization', 'project', 'repo', 'file',
            'technology', 'product', 'location', 'concept', 'document',
            'conversation', 'service'
        )
    ),
    canonical_name TEXT NOT NULL,
    normalized_name TEXT NOT NULL,
    summary TEXT NULL,
    status TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'merged', 'deleted')),
    merged_into_entity_id UUID NULL REFERENCES entities(entity_id),
    attributes_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_entities_namespace_type_name
    ON entities(namespace_id, entity_type, normalized_name);

CREATE INDEX IF NOT EXISTS ix_entities_namespace_status
    ON entities(namespace_id, status);

CREATE INDEX IF NOT EXISTS ix_entities_attributes_json
    ON entities USING GIN(attributes_json);


CREATE TABLE IF NOT EXISTS entity_aliases (
    alias_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_id UUID NOT NULL REFERENCES entities(entity_id) ON DELETE CASCADE,
    alias_text TEXT NOT NULL,
    alias_normalized TEXT NOT NULL,
    source_id UUID NULL REFERENCES sources(source_id),
    confidence NUMERIC(4,3) NOT NULL DEFAULT 0.500 CHECK (confidence >= 0 AND confidence <= 1),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_entity_aliases_entity_alias
    ON entity_aliases(entity_id, alias_normalized);

CREATE INDEX IF NOT EXISTS ix_entity_aliases_alias_normalized
    ON entity_aliases(alias_normalized);


CREATE TABLE IF NOT EXISTS predicate_catalog (
    predicate TEXT PRIMARY KEY,
    description TEXT NOT NULL,
    subject_type TEXT NOT NULL,
    object_kind TEXT NOT NULL CHECK (object_kind IN ('entity', 'string', 'number', 'boolean', 'json')),
    cardinality TEXT NOT NULL CHECK (cardinality IN ('single', 'multi')),
    temporal_policy TEXT NOT NULL CHECK (temporal_policy IN ('atemporal', 'time_scoped', 'status_like')),
    conflict_policy TEXT NOT NULL CHECK (conflict_policy IN ('replace', 'coexist', 'range_split')),
    profile_sync BOOLEAN NOT NULL DEFAULT FALSE,
    task_sync BOOLEAN NOT NULL DEFAULT FALSE,
    default_importance SMALLINT NOT NULL DEFAULT 5 CHECK (default_importance >= 1 AND default_importance <= 10),
    active BOOLEAN NOT NULL DEFAULT TRUE
);


CREATE TABLE IF NOT EXISTS notes (
    note_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    namespace_id TEXT NOT NULL,
    note_type TEXT NOT NULL CHECK (note_type IN ('decision', 'direction', 'hypothesis', 'plan', 'lesson', 'doc_digest', 'context')),
    title TEXT NOT NULL,
    summary TEXT NOT NULL,
    scope_text TEXT NULL,
    scope_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    status TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'superseded', 'retracted', 'resolved', 'stale', 'candidate')),
    retrieval_state TEXT NOT NULL DEFAULT 'active' CHECK (retrieval_state IN ('active', 'warm', 'cold', 'archived', 'deleted')),
    maturity_state TEXT NOT NULL DEFAULT 'fresh' CHECK (maturity_state IN ('fresh', 'stabilizing', 'mature', 'consolidated')),
    maturity_score NUMERIC(4,3) NOT NULL DEFAULT 0.000 CHECK (maturity_score >= 0 AND maturity_score <= 1),
    anchor_entity_id UUID NULL REFERENCES entities(entity_id),
    keywords_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    tags_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    candidate_claims_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    confidence NUMERIC(4,3) NOT NULL CHECK (confidence >= 0 AND confidence <= 1),
    importance SMALLINT NOT NULL DEFAULT 5 CHECK (importance >= 1 AND importance <= 10),
    valid_from TIMESTAMPTZ NULL,
    valid_to TIMESTAMPTZ NULL,
    supersedes_note_id UUID NULL REFERENCES notes(note_id),
    created_from_run_id UUID NULL REFERENCES memory_runs(run_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    embedding VECTOR(1536) NULL,
    use_count INTEGER NOT NULL DEFAULT 0 CHECK (use_count >= 0),
    last_used_at TIMESTAMPTZ NULL,
    last_validated_at TIMESTAMPTZ NULL,
    evidence_count INTEGER NOT NULL DEFAULT 0 CHECK (evidence_count >= 0),
    archived_at TIMESTAMPTZ NULL,
    note_tsv tsvector GENERATED ALWAYS AS (
        to_tsvector('simple', coalesce(title, '') || ' ' || coalesce(summary, '') || ' ' || coalesce(scope_text, ''))
    ) STORED,
    CHECK (valid_to IS NULL OR valid_from IS NULL OR valid_to >= valid_from)
);

CREATE INDEX IF NOT EXISTS ix_notes_namespace_type_status
    ON notes(namespace_id, note_type, status);

CREATE INDEX IF NOT EXISTS ix_notes_namespace_retrieval_maturity
    ON notes(namespace_id, retrieval_state, maturity_state, status);

CREATE INDEX IF NOT EXISTS ix_notes_namespace_anchor_status
    ON notes(namespace_id, anchor_entity_id, status);

CREATE INDEX IF NOT EXISTS ix_notes_namespace_importance
    ON notes(namespace_id, importance DESC, confidence DESC);

CREATE INDEX IF NOT EXISTS ix_notes_keywords_json
    ON notes USING GIN(keywords_json);

CREATE INDEX IF NOT EXISTS ix_notes_tags_json
    ON notes USING GIN(tags_json);

CREATE INDEX IF NOT EXISTS ix_notes_scope_json
    ON notes USING GIN(scope_json);

CREATE INDEX IF NOT EXISTS ix_notes_tsv
    ON notes USING GIN(note_tsv);

CREATE INDEX IF NOT EXISTS ix_notes_embedding_hnsw
    ON notes USING hnsw (embedding vector_cosine_ops);


CREATE TABLE IF NOT EXISTS note_entities (
    note_id UUID NOT NULL REFERENCES notes(note_id) ON DELETE CASCADE,
    entity_id UUID NOT NULL REFERENCES entities(entity_id) ON DELETE CASCADE,
    entity_role TEXT NOT NULL CHECK (entity_role IN ('primary', 'secondary', 'mentioned', 'owner', 'subject')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (note_id, entity_id, entity_role)
);

CREATE INDEX IF NOT EXISTS ix_note_entities_entity
    ON note_entities(entity_id, entity_role);


CREATE TABLE IF NOT EXISTS note_sources (
    note_id UUID NOT NULL REFERENCES notes(note_id) ON DELETE CASCADE,
    source_id UUID NOT NULL REFERENCES sources(source_id) ON DELETE CASCADE,
    support_type TEXT NOT NULL CHECK (support_type IN ('direct', 'summarized', 'imported')),
    evidence_span TEXT NULL,
    evidence_quote TEXT NULL,
    support_confidence NUMERIC(4,3) NOT NULL DEFAULT 1.000 CHECK (support_confidence >= 0 AND support_confidence <= 1),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (note_id, source_id)
);

CREATE INDEX IF NOT EXISTS ix_note_sources_source
    ON note_sources(source_id);


CREATE TABLE IF NOT EXISTS note_links (
    from_note_id UUID NOT NULL REFERENCES notes(note_id) ON DELETE CASCADE,
    to_note_id UUID NOT NULL REFERENCES notes(note_id) ON DELETE CASCADE,
    link_type TEXT NOT NULL CHECK (link_type IN ('supports', 'contradicts', 'refines', 'related', 'supersedes', 'derived_from')),
    link_weight NUMERIC(4,3) NOT NULL DEFAULT 0.500 CHECK (link_weight >= 0 AND link_weight <= 1),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (from_note_id, to_note_id, link_type),
    CHECK (from_note_id <> to_note_id)
);

CREATE INDEX IF NOT EXISTS ix_note_links_to
    ON note_links(to_note_id, link_type);


CREATE TABLE IF NOT EXISTS claims (
    claim_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    namespace_id TEXT NOT NULL,
    subject_entity_id UUID NOT NULL REFERENCES entities(entity_id),
    predicate TEXT NOT NULL REFERENCES predicate_catalog(predicate),
    object_entity_id UUID NULL REFERENCES entities(entity_id),
    object_value_json JSONB NULL,
    normalized_text TEXT NOT NULL,
    context_text TEXT NULL,
    scope_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    qualifiers_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    confidence NUMERIC(4,3) NOT NULL CHECK (confidence >= 0 AND confidence <= 1),
    importance SMALLINT NOT NULL DEFAULT 5 CHECK (importance >= 1 AND importance <= 10),
    status TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'superseded', 'retracted', 'expired', 'candidate')),
    retrieval_state TEXT NOT NULL DEFAULT 'active' CHECK (retrieval_state IN ('active', 'warm', 'cold', 'archived', 'deleted')),
    valid_from TIMESTAMPTZ NULL,
    valid_to TIMESTAMPTZ NULL,
    origin_note_id UUID NULL REFERENCES notes(note_id),
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    supersedes_claim_id UUID NULL REFERENCES claims(claim_id),
    retracted_by_claim_id UUID NULL REFERENCES claims(claim_id),
    created_from_run_id UUID NULL REFERENCES memory_runs(run_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    embedding VECTOR(1536) NULL,
    use_count INTEGER NOT NULL DEFAULT 0 CHECK (use_count >= 0),
    last_used_at TIMESTAMPTZ NULL,
    last_validated_at TIMESTAMPTZ NULL,
    archived_at TIMESTAMPTZ NULL,
    claim_tsv tsvector GENERATED ALWAYS AS (to_tsvector('simple', coalesce(normalized_text, ''))) STORED,
    CHECK (
        (object_entity_id IS NOT NULL AND object_value_json IS NULL)
        OR
        (object_entity_id IS NULL AND object_value_json IS NOT NULL)
    ),
    CHECK (valid_to IS NULL OR valid_from IS NULL OR valid_to >= valid_from)
);

CREATE INDEX IF NOT EXISTS ix_claims_namespace_subject_predicate_status
    ON claims(namespace_id, subject_entity_id, predicate, status, retrieval_state);

CREATE INDEX IF NOT EXISTS ix_claims_namespace_predicate_status
    ON claims(namespace_id, predicate, status);

CREATE INDEX IF NOT EXISTS ix_claims_namespace_validity
    ON claims(namespace_id, valid_from, valid_to);

CREATE INDEX IF NOT EXISTS ix_claims_namespace_importance
    ON claims(namespace_id, importance DESC, confidence DESC);

CREATE INDEX IF NOT EXISTS ix_claims_origin_note
    ON claims(origin_note_id);

CREATE INDEX IF NOT EXISTS ix_claims_qualifiers_json
    ON claims USING GIN(qualifiers_json);

CREATE INDEX IF NOT EXISTS ix_claims_scope_json
    ON claims USING GIN(scope_json);

CREATE INDEX IF NOT EXISTS ix_claims_tsv
    ON claims USING GIN(claim_tsv);

CREATE INDEX IF NOT EXISTS ix_claims_embedding_hnsw
    ON claims USING hnsw (embedding vector_cosine_ops);


CREATE TABLE IF NOT EXISTS claim_sources (
    claim_id UUID NOT NULL REFERENCES claims(claim_id) ON DELETE CASCADE,
    source_id UUID NOT NULL REFERENCES sources(source_id) ON DELETE CASCADE,
    support_type TEXT NOT NULL CHECK (support_type IN ('direct', 'inferred', 'imported', 'derived_from_note')),
    evidence_span TEXT NULL,
    evidence_quote TEXT NULL,
    support_confidence NUMERIC(4,3) NOT NULL DEFAULT 1.000 CHECK (support_confidence >= 0 AND support_confidence <= 1),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (claim_id, source_id)
);

CREATE INDEX IF NOT EXISTS ix_claim_sources_source
    ON claim_sources(source_id);


CREATE TABLE IF NOT EXISTS profiles (
    profile_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    namespace_id TEXT NOT NULL,
    owner_entity_id UUID NOT NULL REFERENCES entities(entity_id),
    profile_json JSONB NOT NULL,
    profile_text TEXT NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    updated_from_run_id UUID NULL REFERENCES memory_runs(run_id),
    last_compacted_at TIMESTAMPTZ NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    profile_tsv tsvector GENERATED ALWAYS AS (to_tsvector('simple', coalesce(profile_text, ''))) STORED
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_profiles_namespace_owner
    ON profiles(namespace_id, owner_entity_id);

CREATE INDEX IF NOT EXISTS ix_profiles_tsv
    ON profiles USING GIN(profile_tsv);

CREATE INDEX IF NOT EXISTS ix_profiles_json
    ON profiles USING GIN(profile_json);


CREATE TABLE IF NOT EXISTS tasks (
    task_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    namespace_id TEXT NOT NULL,
    owner_entity_id UUID NULL REFERENCES entities(entity_id),
    assignee_entity_id UUID NULL REFERENCES entities(entity_id),
    title TEXT NOT NULL,
    description TEXT NULL,
    status TEXT NOT NULL CHECK (status IN ('open', 'in_progress', 'blocked', 'done', 'cancelled')),
    priority TEXT NOT NULL DEFAULT 'normal' CHECK (priority IN ('low', 'normal', 'high')),
    due_at TIMESTAMPTZ NULL,
    scope_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    acceptance_criteria_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    blockers_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    related_entity_ids_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    origin_note_id UUID NULL REFERENCES notes(note_id),
    created_from_run_id UUID NULL REFERENCES memory_runs(run_id),
    confidence NUMERIC(4,3) NOT NULL DEFAULT 0.750 CHECK (confidence >= 0 AND confidence <= 1),
    retrieval_state TEXT NOT NULL DEFAULT 'active' CHECK (retrieval_state IN ('active', 'warm', 'cold', 'archived', 'deleted')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    closed_at TIMESTAMPTZ NULL,
    use_count INTEGER NOT NULL DEFAULT 0 CHECK (use_count >= 0),
    last_used_at TIMESTAMPTZ NULL,
    archived_at TIMESTAMPTZ NULL,
    task_tsv tsvector GENERATED ALWAYS AS (
        to_tsvector('simple', coalesce(title, '') || ' ' || coalesce(description, ''))
    ) STORED
);

CREATE INDEX IF NOT EXISTS ix_tasks_namespace_status_due
    ON tasks(namespace_id, status, due_at);

CREATE INDEX IF NOT EXISTS ix_tasks_namespace_owner_status
    ON tasks(namespace_id, owner_entity_id, status);

CREATE INDEX IF NOT EXISTS ix_tasks_origin_note
    ON tasks(origin_note_id);

CREATE INDEX IF NOT EXISTS ix_tasks_scope_json
    ON tasks USING GIN(scope_json);

CREATE INDEX IF NOT EXISTS ix_tasks_tsv
    ON tasks USING GIN(task_tsv);


CREATE TABLE IF NOT EXISTS task_sources (
    task_id UUID NOT NULL REFERENCES tasks(task_id) ON DELETE CASCADE,
    source_id UUID NOT NULL REFERENCES sources(source_id) ON DELETE CASCADE,
    support_type TEXT NOT NULL CHECK (support_type IN ('direct', 'derived_from_note', 'imported')),
    evidence_span TEXT NULL,
    evidence_quote TEXT NULL,
    support_confidence NUMERIC(4,3) NOT NULL DEFAULT 1.000 CHECK (support_confidence >= 0 AND support_confidence <= 1),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (task_id, source_id)
);

CREATE INDEX IF NOT EXISTS ix_task_sources_source
    ON task_sources(source_id);


CREATE TABLE IF NOT EXISTS episodes (
    episode_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    namespace_id TEXT NOT NULL,
    owner_entity_id UUID NULL REFERENCES entities(entity_id),
    situation TEXT NOT NULL,
    action TEXT NOT NULL,
    result TEXT NOT NULL,
    lesson TEXT NOT NULL,
    tags_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    success_score NUMERIC(4,3) NULL CHECK (success_score IS NULL OR (success_score >= 0 AND success_score <= 1)),
    origin_note_id UUID NULL REFERENCES notes(note_id),
    created_from_run_id UUID NULL REFERENCES memory_runs(run_id),
    source_refs_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    retrieval_state TEXT NOT NULL DEFAULT 'active' CHECK (retrieval_state IN ('active', 'warm', 'cold', 'archived', 'deleted')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    embedding VECTOR(1536) NULL,
    use_count INTEGER NOT NULL DEFAULT 0 CHECK (use_count >= 0),
    last_used_at TIMESTAMPTZ NULL,
    archived_at TIMESTAMPTZ NULL,
    episode_tsv tsvector GENERATED ALWAYS AS (
        to_tsvector('simple', coalesce(situation, '') || ' ' || coalesce(action, '') || ' ' || coalesce(result, '') || ' ' || coalesce(lesson, ''))
    ) STORED
);

CREATE INDEX IF NOT EXISTS ix_episodes_namespace_owner
    ON episodes(namespace_id, owner_entity_id);

CREATE INDEX IF NOT EXISTS ix_episodes_origin_note
    ON episodes(origin_note_id);

CREATE INDEX IF NOT EXISTS ix_episodes_tsv
    ON episodes USING GIN(episode_tsv);

CREATE INDEX IF NOT EXISTS ix_episodes_embedding_hnsw
    ON episodes USING hnsw (embedding vector_cosine_ops);


INSERT INTO predicate_catalog (
    predicate,
    description,
    subject_type,
    object_kind,
    cardinality,
    temporal_policy,
    conflict_policy,
    profile_sync,
    task_sync,
    default_importance,
    active
) VALUES
    ('preferred_name', 'Preferred display name for the subject', 'person', 'string', 'single', 'atemporal', 'replace', TRUE, FALSE, 8, TRUE),
    ('speaks_language', 'Language known or used by the subject', 'person', 'string', 'multi', 'atemporal', 'coexist', TRUE, FALSE, 7, TRUE),
    ('timezone', 'Timezone of the subject', 'person', 'string', 'single', 'status_like', 'replace', TRUE, FALSE, 7, TRUE),
    ('prefers_response_language', 'Preferred language for assistant responses', 'person', 'string', 'single', 'status_like', 'replace', TRUE, FALSE, 9, TRUE),
    ('prefers_answer_detail', 'Preferred answer detail level', 'person', 'string', 'single', 'status_like', 'replace', TRUE, FALSE, 8, TRUE),
    ('prefers_tone', 'Preferred response tone', 'person', 'string', 'single', 'status_like', 'replace', TRUE, FALSE, 7, TRUE),
    ('prefers_code_comment_language', 'Preferred language for code comments', 'person', 'string', 'single', 'status_like', 'replace', TRUE, FALSE, 9, TRUE),
    ('works_with', 'Current or ongoing technology/tool usage', 'person', 'entity', 'multi', 'status_like', 'coexist', TRUE, FALSE, 7, TRUE),
    ('likes', 'Positive preference toward an entity or literal', 'person', 'entity', 'multi', 'atemporal', 'coexist', TRUE, FALSE, 6, TRUE),
    ('has_experience_with', 'Experience with a technology or domain', 'person', 'entity', 'multi', 'atemporal', 'coexist', TRUE, FALSE, 6, TRUE),
    ('has_goal', 'Active or declared goal', 'person', 'string', 'multi', 'status_like', 'coexist', TRUE, TRUE, 8, TRUE),
    ('has_constraint', 'Stable user or project constraint', 'person', 'string', 'multi', 'status_like', 'coexist', TRUE, FALSE, 8, TRUE),
    ('works_on_project', 'Association with a project', 'person', 'entity', 'multi', 'time_scoped', 'range_split', TRUE, TRUE, 7, TRUE),
    ('uses_database', 'Database used by the subject or project', 'project', 'entity', 'multi', 'status_like', 'coexist', FALSE, FALSE, 6, TRUE),
    ('prefers_storage', 'Preferred storage solution', 'person', 'entity', 'multi', 'status_like', 'coexist', TRUE, FALSE, 7, TRUE),
    ('prefers_framework', 'Preferred framework or library', 'person', 'entity', 'multi', 'status_like', 'coexist', TRUE, FALSE, 6, TRUE)
ON CONFLICT (predicate) DO NOTHING;
