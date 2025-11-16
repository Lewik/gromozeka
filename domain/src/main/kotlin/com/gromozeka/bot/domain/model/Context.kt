package com.gromozeka.bot.domain.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * Extracted context snapshot for quick conversation initialization.
 *
 * Context contains curated information about specific topic, feature, or problem.
 * Used to quickly load relevant files, documentation, and knowledge when starting
 * a conversation without manual file selection.
 *
 * This is an immutable value type - use copy() to create modified versions.
 *
 * @property id unique context identifier (UUIDv7)
 * @property projectId project this context belongs to
 * @property name brief context name (e.g., "Authentication Module", "API Design")
 * @property content key information and sufficient details for understanding this context
 * @property files list of relevant files with read specifications
 * @property links related documentation URLs, GitHub issues, design docs
 * @property tags searchable keywords for context discovery
 * @property extractedAt timestamp when context was originally extracted from conversation
 * @property createdAt timestamp when context was first saved
 * @property updatedAt timestamp of last modification
 */
@Serializable
data class Context(
    val id: Id,
    val projectId: Project.Id,
    val name: String,
    val content: String,
    val files: List<File>,
    val links: List<String>,
    val tags: Set<String>,
    val extractedAt: Instant,
    val createdAt: Instant,
    val updatedAt: Instant
) {
    /**
     * Unique context identifier (UUIDv7).
     */
    @Serializable
    @JvmInline
    value class Id(val value: String)

    /**
     * File reference with read specification.
     *
     * @property path filesystem path relative to project root
     * @property spec how to read this file (full content or specific items)
     */
    @Serializable
    data class File(
        val path: String,
        val spec: FileSpec
    )

    /**
     * Specification for reading file content.
     */
    @Serializable
    sealed class FileSpec {
        /**
         * Read entire file content.
         */
        @Serializable
        data object ReadFull : FileSpec()

        /**
         * Read specific items only (functions, classes, line ranges).
         *
         * @property items list of item specifications:
         *   - "fun methodName" - specific function
         *   - "class ClassName" - specific class
         *   - "142:156" - line range
         */
        @Serializable
        data class SpecificItems(val items: List<String>) : FileSpec()
    }
}

/**
 * Converts String to Context.Id for convenience.
 */
fun String.toContextId(): Context.Id = Context.Id(this)
