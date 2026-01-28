package com.gromozeka.domain.model

/**
 * Search mode for unified search operations.
 *
 * Controls search strategy across all entity sources.
 */
enum class SearchMode {
    /**
     * Fulltext keyword search using BM25 algorithm.
     * Fast, exact term matching, no semantic understanding.
     */
    KEYWORD,
    
    /**
     * Vector similarity search using embeddings.
     * Understands meaning and context, conceptual matching.
     * Recommended for natural language queries.
     */
    SEMANTIC,
    
    /**
     * Combined keyword + semantic search.
     * Best for exact term matching with semantic fallback.
     * Note: Scores may be compressed when combining BM25 (unbounded) + Vector (0-1).
     */
    HYBRID
}
