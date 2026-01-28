package com.gromozeka.domain.model

/**
 * Unified search result from any entity source.
 *
 * Uses typed metadata sealed interface for compile-time safety and semantic clarity.
 * Each entity type (conversation, memory, code) has specific metadata structure.
 *
 * @property content search result content (text representation)
 * @property source entity type source (CONVERSATION_MESSAGES, MEMORY_OBJECTS, CODE_SPECS)
 * @property score relevance score (0.0-1.0, higher = more relevant)
 * @property metadata typed metadata specific to entity source
 */
data class UnifiedSearchResult(
    val content: String,
    val source: EntityType,
    val score: Double,
    val metadata: SearchResultMetadata
)

/**
 * Entity type enumeration for unified search.
 */
enum class EntityType {
    CONVERSATION_MESSAGES,
    MEMORY_OBJECTS,
    CODE_SPECS
}
