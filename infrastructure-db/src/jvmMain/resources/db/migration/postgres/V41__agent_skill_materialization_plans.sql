ALTER TABLE agent_skills
    ADD COLUMN materialization_policy VARCHAR(32) NOT NULL DEFAULT 'REQUIRED',
    ADD COLUMN materialization_reason TEXT NOT NULL DEFAULT 'Imported before workspace materialization analysis was introduced.',
    ADD COLUMN materialization_model_configuration_id VARCHAR(255) NULL,
    ADD COLUMN materialization_analyzed_at TIMESTAMPTZ NULL;

ALTER TABLE agent_skills
    ALTER COLUMN materialization_policy DROP DEFAULT,
    ALTER COLUMN materialization_reason DROP DEFAULT;

ALTER TABLE agent_skills
    ADD CONSTRAINT ck_agent_skills_materialization_policy
        CHECK (materialization_policy IN ('REQUIRED', 'NOT_REQUIRED')),
    ADD CONSTRAINT ck_agent_skills_materialization_provenance
        CHECK (
            (materialization_model_configuration_id IS NULL AND materialization_analyzed_at IS NULL)
            OR
            (materialization_model_configuration_id IS NOT NULL AND materialization_analyzed_at IS NOT NULL)
        );
