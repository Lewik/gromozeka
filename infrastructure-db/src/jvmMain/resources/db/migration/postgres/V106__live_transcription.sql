DELETE FROM ai_runtime_assignments
WHERE purpose IN ('LIVE_TRANSCRIPT_STABILIZER', 'LIVE_TRANSLATION');

UPDATE runtime_catalog_configuration SET revision = revision + 1;
