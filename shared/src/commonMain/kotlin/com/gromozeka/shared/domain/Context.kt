package com.gromozeka.shared.domain

import com.gromozeka.shared.domain.Project
import kotlin.time.Instant
import kotlinx.serialization.Serializable

/**
 * Extracted conversation context for efficient reuse and organization.
 *
 * Contexts are distilled knowledge extracted from conversations, containing:
 * - Key information and decisions
 * - Relevant file references with specific sections or full content
 * - External links and resources
 * - Searchable tags
 *
 * Contexts enable:
 * - Quick context loading without replaying full conversation
 * - Sharing knowledge between conversations
 * - Organizing project knowledge by topic
 *
 * Each context is project-scoped and tracks when it was extracted from conversation.
 *
 * This is an immutable value type - use copy() to create modified versions.
 *
 * @property id unique context identifier (time-based UUID for sortability)
 * @property projectId project this context belongs to
 * @property name brief topic name (e.g., "API Design", "Database Schema")
 * @property content key information and decisions (markdown-formatted text)
 * @property files list of file references with read specifications (full file or specific sections)
 * @property links external resources and documentation URLs
 * @property tags searchable keywords for categorization and discovery
 * @property extractedAt timestamp when context was extracted from conversation
 * @property createdAt timestamp when context entity was created (immutable)
 * @property updatedAt timestamp of last modification (content, files, links, or tags change)
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
    @Serializable
    @JvmInline
    value class Id(val value: String)

    /**
     * File reference with read specification.
     *
     * Specifies which parts of the file should be loaded when context is used:
     * - Full file content (ReadFull)
     * - Specific items: functions, classes, or line ranges (SpecificItems)
     *
     * @property path file path relative to project root
     * @property spec read specification (full or specific items)
     */
    @Serializable
    data class File(
        val path: String,
        val spec: FileSpec
    )

    /**
     * Specification for which parts of file to read.
     *
     * Enables selective file loading for large files where only specific
     * sections are relevant to the context.
     */
    @Serializable
    sealed class FileSpec {
        /**
         * Read entire file content.
         */
        @Serializable
        data object ReadFull : FileSpec()

        /**
         * Read specific file sections.
         *
         * Items can be:
         * - Function names (e.g., "fun methodName")
         * - Class names (e.g., "class ClassName")
         * - Line ranges (e.g., "142:156")
         *
         * @property items list of item specifications to extract
         */
        @Serializable
        data class SpecificItems(val items: List<String>) : FileSpec()
    }
}

/**
 * Converts string to Context.Id.
 *
 * Convenience extension for creating Context.Id from string values.
 *
 * @receiver string value to wrap as Context.Id
 * @return Context.Id instance
 */
fun String.toContextId(): Context.Id = Context.Id(this)
