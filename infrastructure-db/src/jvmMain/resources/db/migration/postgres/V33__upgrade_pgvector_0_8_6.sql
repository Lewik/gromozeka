DO $migration$
DECLARE
    current_version text;
BEGIN
    SELECT extversion
    INTO STRICT current_version
    FROM pg_extension
    WHERE extname = 'vector';

    IF current_version <> '0.8.6'
        AND EXISTS (
            SELECT 1
            FROM pg_extension_update_paths('vector')
            WHERE source = current_version
              AND target = '0.8.6'
              AND path IS NOT NULL
        )
    THEN
        ALTER EXTENSION vector UPDATE TO '0.8.6';
    END IF;
END
$migration$;

REINDEX INDEX idx_memory_embeddings_1536_hnsw;
REINDEX INDEX idx_memory_embeddings_2560_hnsw;
REINDEX INDEX idx_memory_embeddings_3072_hnsw;

-- Keep the historical Flyway checksum stable for installations where V33 is already applied: gqt10y
