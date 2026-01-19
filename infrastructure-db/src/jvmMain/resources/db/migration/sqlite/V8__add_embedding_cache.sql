-- Embedding Cache table
CREATE TABLE embedding_cache (
    text_hash TEXT PRIMARY KEY,
    embedding_vector TEXT NOT NULL,
    model TEXT NOT NULL,
    created_at INTEGER NOT NULL
);

CREATE INDEX idx_embedding_cache_model ON embedding_cache(model);
CREATE INDEX idx_embedding_cache_created ON embedding_cache(created_at);
