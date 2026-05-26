CREATE TABLE memory_embeddings (
    id TEXT PRIMARY KEY,
    namespace TEXT NOT NULL,
    memory_type TEXT NOT NULL,
    memory_id TEXT NOT NULL,
    model_configuration_id TEXT NOT NULL,
    provider_model_id TEXT NOT NULL,
    dimensions INTEGER NOT NULL,
    content_hash TEXT NOT NULL,
    embedding_1536 vector(1536) NULL,
    embedding_3072 vector(3072) NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CHECK (
        (dimensions = 1536 AND embedding_1536 IS NOT NULL AND embedding_3072 IS NULL)
        OR
        (dimensions = 3072 AND embedding_3072 IS NOT NULL AND embedding_1536 IS NULL)
    )
);

CREATE UNIQUE INDEX idx_memory_embeddings_item_model
    ON memory_embeddings(namespace, memory_type, memory_id, model_configuration_id);

CREATE INDEX idx_memory_embeddings_lookup
    ON memory_embeddings(namespace, model_configuration_id, dimensions, memory_type);

CREATE INDEX idx_memory_embeddings_1536_hnsw
    ON memory_embeddings USING hnsw (embedding_1536 vector_cosine_ops)
    WHERE embedding_1536 IS NOT NULL;

CREATE INDEX idx_memory_embeddings_3072_hnsw
    ON memory_embeddings USING hnsw (embedding_3072 vector_cosine_ops)
    WHERE embedding_3072 IS NOT NULL;
