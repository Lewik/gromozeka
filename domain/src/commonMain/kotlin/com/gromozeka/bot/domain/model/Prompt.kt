package com.gromozeka.domain.model

import com.gromozeka.shared.path.KPath
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * Single reusable prompt fragment.
 *
 * Prompts are building blocks for agent system prompts.
 * Agent contains ordered list of prompt IDs that are assembled into final system prompt.
 *
 * This is an immutable value type - use copy() to create modified versions.
 *
 * @property id unique prompt identifier
 * @property name human-readable prompt name (displayed in UI)
 * @property content markdown text of the prompt
 * @property source origin of this prompt (builtin/file/remote)
 * @property createdAt timestamp when prompt was created (immutable)
 * @property updatedAt timestamp of last modification
 */
@Serializable
data class Prompt(
    val id: Id,
    val name: String,
    val content: String,
    val source: Source,
    val createdAt: Instant,
    val updatedAt: Instant
) {
    /**
     * Unique prompt identifier.
     */
    @Serializable
    @JvmInline
    value class Id(val value: String)

    /**
     * Origin of a prompt.
     */
    @Serializable
    sealed interface Source {
        /**
         * Builtin prompt shipped with application.
         * Embedded in resources, read-only.
         *
         * @property resourcePath path to resource file (e.g., "agents/architect-agent.md")
         */
        @Serializable
        data class Builtin(val resourcePath: KPath) : Source

        /**
         * Prompt stored as local file on disk.
         * All local file prompts are editable in external editor (IDEA).
         *
         * @property path path to file (absolute or relative depending on type)
         */
        sealed interface LocalFile : Source {
            val path: KPath

            /**
             * User-created prompt in user folder.
             *
             * @property path path relative to user prompts directory
             */
            @Serializable
            data class User(override val path: KPath) : LocalFile

            /**
             * Prompt from global Claude configuration.
             *
             * @property path path relative to ~/.claude/prompts/
             */
            @Serializable
            data class ClaudeGlobal(override val path: KPath) : LocalFile

            /**
             * Prompt from project-specific Claude configuration.
             *
             * @property projectPath path to project root
             * @property promptPath path relative to project/.claude/prompts/
             */
            @Serializable
            data class ClaudeProject(
                val projectPath: KPath,
                val promptPath: KPath
            ) : LocalFile {
                override val path: KPath
                    get() = KPath("${projectPath.value}/.claude/prompts/${promptPath.value}")
            }

            /**
             * Imported prompt from arbitrary file.
             *
             * @property path absolute path to imported file
             */
            @Serializable
            data class Imported(override val path: KPath) : LocalFile
        }

        /**
         * Prompt from remote source.
         */
        sealed interface Remote : Source {
            /**
             * Prompt downloaded from URL.
             *
             * @property url source URL
             */
            @Serializable
            data class Url(val url: String) : Remote
        }

        /**
         * Inline text prompt.
         * Used for ad-hoc prompts created dynamically (e.g., via MCP tools).
         *
         * @property text inline prompt content
         */
        @Serializable
        data class Text(val text: String) : Source
    }

    /**
     * Thrown when referenced prompt is not found.
     *
     * @property promptId ID of missing prompt
     */
    class NotFoundException(val promptId: Id) :
        Exception("Prompt not found: ${promptId.value}")
}
