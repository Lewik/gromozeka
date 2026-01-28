package com.gromozeka.domain.model

import kotlinx.datetime.Instant

/**
 * Typed metadata for unified search results.
 *
 * Provides compile-time safety and semantic clarity for different entity types.
 * Each variant contains entity-specific fields with proper types (no string parsing).
 */
sealed interface SearchResultMetadata {
    
    /**
     * Metadata for conversation message search results.
     *
     * @property messageId unique message identifier
     * @property threadId thread containing this message
     * @property conversationId conversation containing this message
     * @property role message author role (USER, ASSISTANT, SYSTEM)
     * @property createdAt when message was created (transaction-time)
     */
    data class ConversationMessage(
        val messageId: String,
        val threadId: String,
        val conversationId: String,
        val role: MessageRole,
        val createdAt: Instant
    ) : SearchResultMetadata
    
    /**
     * Metadata for memory object (knowledge graph) search results.
     *
     * Supports bi-temporal model:
     * - createdAt: transaction-time (when recorded in DB)
     * - validAt: valid-time (when fact became true in reality)
     * - invalidAt: when fact was invalidated (soft delete)
     *
     * @property objectId unique memory object identifier
     * @property objectType entity type (Person, Technology, Concept, etc.)
     * @property createdAt when recorded in knowledge graph
     * @property validAt when fact became valid in reality (null = same as createdAt)
     * @property invalidAt when fact was invalidated (null = still valid)
     */
    data class MemoryObject(
        val objectId: String,
        val objectType: String,
        val createdAt: Instant,
        val validAt: Instant?,
        val invalidAt: Instant?
    ) : SearchResultMetadata {
        /**
         * Check if memory object is currently valid.
         * @return true if not invalidated
         */
        fun isValid(): Boolean = invalidAt == null
    }
    
    /**
     * Metadata for code specification search results.
     *
     * @property symbolName fully qualified symbol name
     * @property symbolKind class, interface, method, property, etc.
     * @property filePath source file path relative to project root
     * @property lineNumber line number where symbol is defined
     * @property projectId project identifier
     * @property lastModified last git commit time for this file (null if not available)
     */
    data class CodeSpec(
        val symbolName: String,
        val symbolKind: String,
        val filePath: String,
        val lineNumber: Int,
        val projectId: String,
        val lastModified: Instant?
    ) : SearchResultMetadata
}

/**
 * Message role enumeration.
 */
enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM
}
