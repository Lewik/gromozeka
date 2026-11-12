-- A non-secret issuance marker prevents a retired command from minting another grant after GC.
ALTER TABLE background_activity_origins
    ADD COLUMN script_grant_issued BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN command_closed_at TIMESTAMPTZ;

CREATE TABLE command_script_grants (
    command_id VARCHAR(128) PRIMARY KEY,
    conversation_id VARCHAR(255) NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    actor_user_id VARCHAR(255) NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    project_id VARCHAR(255) NOT NULL,
    token_hash VARCHAR(64) NOT NULL UNIQUE CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    origin_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    checked_at TIMESTAMPTZ NOT NULL,
    last_reminder_day BIGINT NOT NULL DEFAULT 0 CHECK (last_reminder_day >= 0),
    revoked_at TIMESTAMPTZ
);
-- The creating Worker RPC has a shorter retention period than a running command.
-- Its identity remains in origin_json, deliberately without a cascading foreign key.
CREATE INDEX command_script_grants_maintenance ON command_script_grants(checked_at, command_id) WHERE revoked_at IS NULL;
CREATE INDEX command_script_grants_gc ON command_script_grants(revoked_at) WHERE revoked_at IS NOT NULL;
