ALTER TABLE memory_embeddings
    ADD COLUMN embedding_2560 halfvec(2560) NULL;

DO $$
DECLARE
    existing_constraint_name TEXT;
BEGIN
    SELECT conname
    INTO existing_constraint_name
    FROM pg_constraint
    WHERE conrelid = 'memory_embeddings'::regclass
      AND contype = 'c'
      AND pg_get_constraintdef(oid) LIKE '%dimensions = 1536%'
      AND pg_get_constraintdef(oid) LIKE '%dimensions = 3072%'
    LIMIT 1;

    IF existing_constraint_name IS NOT NULL THEN
        EXECUTE format('ALTER TABLE memory_embeddings DROP CONSTRAINT %I', existing_constraint_name);
    END IF;
END $$;

ALTER TABLE memory_embeddings
    ADD CONSTRAINT memory_embeddings_dimension_column_check CHECK (
        (
            dimensions = 1536
            AND embedding_1536 IS NOT NULL
            AND embedding_2560 IS NULL
            AND embedding_3072 IS NULL
        )
        OR
        (
            dimensions = 2560
            AND embedding_1536 IS NULL
            AND embedding_2560 IS NOT NULL
            AND embedding_3072 IS NULL
        )
        OR
        (
            dimensions = 3072
            AND embedding_1536 IS NULL
            AND embedding_2560 IS NULL
            AND embedding_3072 IS NOT NULL
        )
    );

CREATE INDEX idx_memory_embeddings_2560_hnsw
    ON memory_embeddings USING hnsw (embedding_2560 halfvec_cosine_ops)
    WHERE embedding_2560 IS NOT NULL;
