ALTER EXTENSION vector UPDATE TO '0.8.6';

REINDEX INDEX idx_memory_embeddings_1536_hnsw;
REINDEX INDEX idx_memory_embeddings_2560_hnsw;
REINDEX INDEX idx_memory_embeddings_3072_hnsw;
