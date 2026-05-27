ALTER TABLE memory_sources
    ALTER COLUMN search_text SET DEFAULT '';

UPDATE memory_sources
SET search_text = ''
WHERE search_text IS NULL;

ALTER TABLE memory_sources
    ALTER COLUMN search_text SET NOT NULL;

ALTER TABLE memory_runs
    ADD COLUMN IF NOT EXISTS search_text TEXT NOT NULL DEFAULT '';

ALTER TABLE memory_entities
    ADD COLUMN IF NOT EXISTS search_text TEXT NOT NULL DEFAULT '';

ALTER TABLE memory_claims
    ADD COLUMN IF NOT EXISTS search_text TEXT NOT NULL DEFAULT '';

ALTER TABLE memory_claims
    ADD COLUMN IF NOT EXISTS evidence_source_ids TEXT[] NOT NULL DEFAULT '{}',
    ADD COLUMN IF NOT EXISTS supersedes_claim_id TEXT NULL,
    ADD COLUMN IF NOT EXISTS retracted_by_claim_id TEXT NULL;

ALTER TABLE memory_notes
    ADD COLUMN IF NOT EXISTS search_text TEXT NOT NULL DEFAULT '';

ALTER TABLE memory_notes
    ADD COLUMN IF NOT EXISTS evidence_source_ids TEXT[] NOT NULL DEFAULT '{}',
    ADD COLUMN IF NOT EXISTS entity_ids TEXT[] NOT NULL DEFAULT '{}',
    ADD COLUMN IF NOT EXISTS supersedes_note_id TEXT NULL;

ALTER TABLE memory_tasks
    ADD COLUMN IF NOT EXISTS search_text TEXT NOT NULL DEFAULT '';

ALTER TABLE memory_tasks
    ADD COLUMN IF NOT EXISTS evidence_source_ids TEXT[] NOT NULL DEFAULT '{}',
    ADD COLUMN IF NOT EXISTS entity_ids TEXT[] NOT NULL DEFAULT '{}';

ALTER TABLE memory_profiles
    ADD COLUMN IF NOT EXISTS search_text TEXT NOT NULL DEFAULT '';

ALTER TABLE memory_episodes
    ADD COLUMN IF NOT EXISTS search_text TEXT NOT NULL DEFAULT '';

ALTER TABLE memory_episodes
    ADD COLUMN IF NOT EXISTS evidence_source_ids TEXT[] NOT NULL DEFAULT '{}';

CREATE INDEX IF NOT EXISTS idx_memory_runs_search_text_trgm
    ON memory_runs USING GIN(search_text gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_memory_entities_search_text_trgm
    ON memory_entities USING GIN(search_text gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_memory_claims_search_text_trgm
    ON memory_claims USING GIN(search_text gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_memory_claims_evidence_sources
    ON memory_claims USING GIN(evidence_source_ids);

CREATE INDEX IF NOT EXISTS idx_memory_claims_supersedes
    ON memory_claims(namespace, supersedes_claim_id, status);

CREATE INDEX IF NOT EXISTS idx_memory_claims_retracted_by
    ON memory_claims(namespace, retracted_by_claim_id);

CREATE INDEX IF NOT EXISTS idx_memory_notes_search_text_trgm
    ON memory_notes USING GIN(search_text gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_memory_notes_evidence_sources
    ON memory_notes USING GIN(evidence_source_ids);

CREATE INDEX IF NOT EXISTS idx_memory_notes_entity_ids
    ON memory_notes USING GIN(entity_ids);

CREATE INDEX IF NOT EXISTS idx_memory_notes_supersedes
    ON memory_notes(namespace, supersedes_note_id, status);

CREATE INDEX IF NOT EXISTS idx_memory_tasks_search_text_trgm
    ON memory_tasks USING GIN(search_text gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_memory_tasks_evidence_sources
    ON memory_tasks USING GIN(evidence_source_ids);

CREATE INDEX IF NOT EXISTS idx_memory_tasks_entity_ids
    ON memory_tasks USING GIN(entity_ids);

CREATE INDEX IF NOT EXISTS idx_memory_profiles_search_text_trgm
    ON memory_profiles USING GIN(search_text gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_memory_episodes_search_text_trgm
    ON memory_episodes USING GIN(search_text gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_memory_episodes_evidence_sources
    ON memory_episodes USING GIN(evidence_source_ids);
