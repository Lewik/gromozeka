DELETE FROM ai_admission_leases
WHERE background_session_id IS NOT NULL;

ALTER TABLE ai_admission_leases
    DROP COLUMN background_session_id,
    DROP COLUMN accepted;
