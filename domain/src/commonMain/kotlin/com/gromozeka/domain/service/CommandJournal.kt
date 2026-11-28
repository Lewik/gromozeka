package com.gromozeka.domain.service

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.Workspace
import com.gromozeka.domain.model.WorkspaceMount
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline
import kotlin.time.Instant

/**
 * Optional sidecar history for managed commands and their nested MCP calls.
 * Conversation remains the provider-facing causal history.
 */
interface CommandJournal {
    suspend fun admit(
        entry: Entry,
        rootCall: Call,
        content: List<Content> = emptyList(),
    ): Entry

    suspend fun append(
        call: Call,
        content: List<Content> = emptyList(),
    ): Call

    suspend fun append(
        event: Event,
        content: List<Content> = emptyList(),
    ): Event

    suspend fun findEntry(id: Entry.Id): Entry?

    suspend fun findEntryBySource(
        conversationId: Conversation.Id,
        sourceToolCallId: ContentItem.ToolCall.Id,
    ): Entry?

    suspend fun findCallBySource(
        conversationId: Conversation.Id,
        sourceToolCallId: ContentItem.ToolCall.Id,
    ): Call?

    suspend fun findEntryByCommandTask(commandTaskId: CommandTask.Id): Entry?

    suspend fun calls(entryId: Entry.Id): List<Call>

    suspend fun events(entryId: Entry.Id): List<Event>

    suspend fun content(entryId: Entry.Id): List<Content>

    /** Bounded read model for tools and administrative inspection. */
    interface Reader {
        suspend fun search(query: SearchQuery): SearchPage

        suspend fun get(
            projectId: Project.Id,
            entryId: Entry.Id,
            callLimit: Int = 100,
            eventLimit: Int = 200,
        ): Snapshot?

        suspend fun readContent(query: ContentQuery): ContentPage?

        suspend fun statistics(projectId: Project.Id): Statistics
    }

    @Serializable
    data class SearchQuery(
        val projectId: Project.Id,
        val conversationId: Conversation.Id? = null,
        val actorUserId: User.Id? = null,
        val agentDefinitionId: AgentDefinition.Id? = null,
        val workerId: ConversationRuntimeWorkerId? = null,
        val origin: Entry.Origin? = null,
        val toolName: String? = null,
        val eventKind: Event.Kind? = null,
        val text: String? = null,
        val from: Instant? = null,
        val to: Instant? = null,
        val before: Cursor? = null,
        val limit: Int = 20,
    ) {
        init {
            require(limit in 1..50) { "Command Journal search limit must be between 1 and 50" }
            require(toolName == null || toolName.isNotBlank()) { "Command Journal tool name filter must not be blank" }
            require(text == null || text.isNotBlank()) { "Command Journal text filter must not be blank" }
            require(from == null || to == null || from <= to) { "Command Journal time range is invalid" }
        }
    }

    @Serializable
    data class Cursor(
        val createdAt: Instant,
        val entryId: Entry.Id,
    )

    @Serializable
    data class SearchPage(
        val entries: List<Summary>,
        val nextCursor: Cursor? = null,
    )

    @Serializable
    data class Summary(
        val entry: Entry,
        val rootCall: Call,
        val latestEvent: Event? = null,
        val callCount: Long,
        val eventCount: Long,
        val linkedContentBytes: Long,
        val excerpt: String? = null,
    )

    @Serializable
    data class Snapshot(
        val entry: Entry,
        val calls: List<Call>,
        val events: List<Event>,
        val callCount: Long,
        val eventCount: Long,
        val callsTruncated: Boolean,
        val eventsTruncated: Boolean,
    )

    @Serializable
    data class ContentQuery(
        val projectId: Project.Id,
        val entryId: Entry.Id,
        val callId: Call.Id? = null,
        val eventId: Event.Id? = null,
        val kinds: Set<Content.Kind> = emptySet(),
        val afterLinkId: Long? = null,
        val limit: Int = 10,
        val maxCharactersPerItem: Int = 8_192,
    ) {
        init {
            require(afterLinkId == null || afterLinkId >= 0) { "Command Journal content cursor is invalid" }
            require(limit in 1..50) { "Command Journal content limit must be between 1 and 50" }
            require(maxCharactersPerItem in 256..65_536) {
                "Command Journal content character limit must be between 256 and 65536"
            }
            require(limit.toLong() * maxCharactersPerItem <= 131_072) {
                "Command Journal content page must not exceed 131072 requested characters"
            }
        }
    }

    @Serializable
    data class ContentPage(
        val items: List<ContentRecord>,
        val nextLinkId: Long? = null,
    )

    @Serializable
    data class ContentRecord(
        val linkId: Long,
        val entryId: Entry.Id,
        val callId: Call.Id,
        val eventId: Event.Id? = null,
        val contentId: Content.Id,
        val kind: Content.Kind,
        val sequence: Int,
        val text: String,
        val sizeBytes: Int,
        val textTruncated: Boolean,
        val startByte: Long? = null,
        val endByte: Long? = null,
        val totalBytes: Long? = null,
        val truncatedBefore: Boolean = false,
        val truncatedAfter: Boolean = false,
        val createdAt: Instant,
    )

    @Serializable
    data class Statistics(
        val projectId: Project.Id,
        val entryCount: Long,
        val callCount: Long,
        val eventCount: Long,
        val contentLinkCount: Long,
        val uniqueContentCount: Long,
        val uniqueContentBytes: Long,
        val journalStorageBytes: Long,
        val databaseBytes: Long,
        val measuredAt: Instant,
    )

    /** Trusted call provenance supplied by the Harness, never by public tool arguments. */
    data class Source(
        val kind: Kind,
        val sourceRuntimeTaskId: ConversationRuntimeTask.Id? = null,
        val parentCommandTaskId: CommandTask.Id? = null,
    ) {
        init {
            require(kind != Kind.CONVERSATION_TOOL_CALL || sourceRuntimeTaskId != null) {
                "A conversation Command Journal source requires its Runtime task"
            }
            require(kind != Kind.COMMAND_SDK_MCP || parentCommandTaskId != null) {
                "A command SDK source requires its parent command task"
            }
        }

        enum class Kind {
            CONVERSATION_TOOL_CALL,
            COMMAND_SDK_MCP,
            DIRECT_MCP,
        }
    }

    @Serializable
    data class Entry(
        val id: Id,
        val origin: Origin,
        val actorUserId: User.Id,
        val projectId: Project.Id,
        val conversationId: Conversation.Id,
        val threadId: Conversation.Thread.Id,
        val agentDefinitionId: AgentDefinition.Id? = null,
        val sourceRuntimeTaskId: ConversationRuntimeTask.Id? = null,
        val sourceToolCallId: ContentItem.ToolCall.Id? = null,
        val parentEntryId: Id? = null,
        val createdAt: Instant,
    ) {
        init {
            require(sourceToolCallId != null || origin == Origin.VISUAL_HANDLER) {
                "A Command Journal entry without a source tool call must be a Visual handler"
            }
        }

        @Serializable
        @JvmInline
        value class Id(val value: String) {
            init {
                require(value.isNotBlank() && value.length <= 128) { "Invalid Command Journal entry id" }
            }
        }

        @Serializable
        enum class Origin {
            CONVERSATION_TOOL_CALL,
            DIRECT_MCP,
            VISUAL_HANDLER,
            REPORTED_BY_CALLER,
        }
    }

    @Serializable
    data class Call(
        val id: Id,
        val entryId: Entry.Id,
        val parentId: Id? = null,
        val sourceToolCallId: ContentItem.ToolCall.Id? = null,
        val kind: Kind,
        val toolName: String,
        val target: Target,
        val createdAt: Instant,
    ) {
        init {
            require(toolName.isNotBlank()) { "Command Journal call tool name must not be blank" }
            require(parentId != id) { "Command Journal call cannot parent itself" }
        }

        @Serializable
        @JvmInline
        value class Id(val value: String) {
            init {
                require(value.isNotBlank() && value.length <= 128) { "Invalid Command Journal call id" }
            }
        }

        @Serializable
        enum class Kind {
            COMMAND,
            PYTHON,
            MONITOR,
            INPUT,
            TOOL,
        }

        @Serializable
        data class Target(
            val workerId: ConversationRuntimeWorkerId? = null,
            val workspaceId: Workspace.Id? = null,
            val workspaceMountId: WorkspaceMount.Id? = null,
            val workspaceRootPath: String? = null,
            val workingDirectory: String? = null,
        ) {
            init {
                require(workspaceMountId == null || workerId != null) {
                    "A Command Journal workspace mount requires its Worker"
                }
                require(workspaceRootPath == null || workspaceMountId != null) {
                    "A Command Journal workspace root requires its mount"
                }
            }
        }
    }

    @Serializable
    data class Event(
        val id: Id,
        val entryId: Entry.Id,
        val callId: Call.Id,
        val sourceKey: String,
        val kind: Kind,
        val occurredAt: Instant,
        val commandTaskId: CommandTask.Id? = null,
        val statusMessage: String? = null,
        val exitCode: Int? = null,
    ) {
        init {
            require(sourceKey.isNotBlank() && sourceKey.length <= 255) {
                "Invalid Command Journal event source key"
            }
            require(statusMessage == null || statusMessage.length <= 4_096) {
                "Command Journal status message must not exceed 4096 characters"
            }
        }

        @Serializable
        @JvmInline
        value class Id(val value: String) {
            init {
                require(value.isNotBlank() && value.length <= 128) { "Invalid Command Journal event id" }
            }
        }

        @Serializable
        enum class Kind {
            ACCEPTED,
            DISPATCHED,
            STARTED,
            COMPLETED,
            FAILED,
            CANCELLED,
            OUTCOME_UNKNOWN,
            REPORTED,
        }
    }

    @Serializable
    data class Content(
        val id: Id,
        val entryId: Entry.Id,
        val callId: Call.Id,
        val eventId: Event.Id? = null,
        val kind: Kind,
        val sequence: Int = 0,
        val text: String,
        val startByte: Long? = null,
        val endByte: Long? = null,
        val totalBytes: Long? = null,
        val truncatedBefore: Boolean = false,
        val truncatedAfter: Boolean = false,
        val createdAt: Instant,
    ) {
        init {
            require(sequence >= 0) { "Command Journal content sequence must be non-negative" }
            require(text.isNotEmpty()) { "Command Journal content must not be empty" }
            require((startByte == null) == (endByte == null)) {
                "Command Journal content byte range must be complete"
            }
            if (startByte != null) {
                require(startByte >= 0 && endByte!! >= startByte) { "Invalid Command Journal content byte range" }
                require(totalBytes == null || totalBytes >= endByte) { "Command Journal total bytes precede its content range" }
            }
        }

        val sizeBytes: Int
            get() = text.encodeToByteArray().size

        @Serializable
        @JvmInline
        value class Id(val value: String) {
            init {
                require(value.matches(Regex("[0-9a-f]{64}"))) { "Command Journal content id must be SHA-256" }
            }
        }

        @Serializable
        enum class Kind {
            ARGUMENTS,
            COMMAND,
            PYTHON_SOURCE,
            OUTPUT,
            STDERR,
            REPORTED_OUTPUT,
        }
    }
}
