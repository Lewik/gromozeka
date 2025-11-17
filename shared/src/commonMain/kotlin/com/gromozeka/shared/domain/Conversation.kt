package com.gromozeka.shared.domain

import com.gromozeka.shared.domain.Project
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonElement
import kotlin.time.Instant

/**
 * Represents a complete conversation session with an AI agent.
 *
 * A conversation belongs to a project and uses a specific AI model. It maintains
 * conversation history through a sequence of threads, where each thread represents
 * a linear message history. Threads are immutable by default - editing, deleting,
 * or squashing messages creates new threads while preserving original history.
 *
 * This is an immutable value type - use copy() to create modified versions.
 *
 * @property id unique conversation identifier (time-based UUID for sortability)
 * @property projectId project this conversation belongs to
 * @property displayName human-readable conversation title (empty string if not set)
 * @property aiProvider AI provider name (e.g., "OLLAMA", "ANTHROPIC", "OPENAI")
 * @property modelName model identifier (e.g., "claude-3-5-sonnet", "llama3.2")
 * @property currentThread active thread ID where new messages are appended
 * @property createdAt timestamp when conversation was created (immutable)
 * @property updatedAt timestamp of last modification (updated on new messages)
 */
@Serializable
data class Conversation(
    val id: Id,
    val projectId: Project.Id,
    val displayName: String = "",

    val aiProvider: String,
    val modelName: String,

    val currentThread: Thread.Id,

    val createdAt: Instant,
    val updatedAt: Instant,
) {
    @Serializable
    @JvmInline
    value class Id(val value: String)

    /**
     * Linear sequence of messages in a conversation.
     *
     * Threads are append-only by default - new messages are added to the end.
     * Explicit operations (delete, edit, squash) create new threads while preserving
     * the original thread for history and undo functionality.
     *
     * Each thread tracks its relationship to the original thread via [originalThread].
     * Null originalThread indicates initial thread or pure append-only thread.
     * Non-null originalThread indicates this thread was derived via an explicit operation.
     *
     * This is an immutable value type - use copy() to create modified versions.
     *
     * @property id unique thread identifier (time-based UUID for sortability)
     * @property conversationId conversation this thread belongs to
     * @property originalThread source thread if this was created by delete/edit/squash, null for initial/append-only threads
     * @property lastTurnNumber sequence number of last AI turn (incremented on each AI response)
     * @property createdAt timestamp when thread was created (immutable)
     * @property updatedAt timestamp of last message added to this thread
     */
    @Serializable
    data class Thread(
        val id: Id,
        val conversationId: Conversation.Id,

        val originalThread: Id? = null,

        val lastTurnNumber: Int = 0,

        val createdAt: Instant,
        val updatedAt: Instant,
    ) {
        @Serializable
        @JvmInline
        value class Id(val value: String)
    }

    /**
     * Single message in a conversation thread.
     *
     * Messages are immutable once created. To edit a message, create a new message
     * and reference the original via squash operation tracking.
     *
     * Messages can contain multiple content items (text, tool calls, tool results, images).
     * The [role] determines message authorship (user, assistant, system).
     *
     * Squash operations (AI-powered summarization/restructuring) are tracked via
     * [squashOperationId] for reproducibility and audit trail. Legacy [originalIds]
     * field is deprecated in favor of structured SquashOperation tracking.
     *
     * This is an immutable value type - once created, messages cannot be modified.
     *
     * @property id unique message identifier (time-based UUID for sortability)
     * @property conversationId conversation this message belongs to
     * @property originalIds DEPRECATED - list of message IDs this message replaces (use squashOperationId instead)
     * @property squashOperationId reference to squash operation that created this message (null for normal messages)
     * @property replyTo message this is replying to (null for non-reply messages, used for branching conversations)
     * @property role message author (USER, ASSISTANT, SYSTEM)
     * @property content list of content items (text, tool calls, results, images, thinking blocks)
     * @property instructions metadata instructions for message processing (response routing, source tracking)
     * @property createdAt timestamp when message was created (immutable)
     */
    @Serializable
    data class Message(
        val id: Id,
        val conversationId: Conversation.Id,

        @Deprecated("Use squashOperationId instead for structured squash provenance")
        val originalIds: List<Id> = emptyList(),
        val squashOperationId: SquashOperation.Id? = null,
        val replyTo: Id? = null,

        val role: Role,
        val content: List<ContentItem>,
        val instructions: List<Instruction> = emptyList(),

        val createdAt: Instant,
    ) {
        @Serializable
        @JvmInline
        value class Id(val value: String)

        /**
         * Message author role.
         *
         * Determines message processing and UI presentation:
         * - USER: Human input or system-generated user messages
         * - ASSISTANT: AI model responses
         * - SYSTEM: System notifications and control messages
         */
        @Serializable
        enum class Role {
            USER, ASSISTANT, SYSTEM
        }

        /**
         * Polymorphic content item within a message.
         *
         * Messages can contain heterogeneous content: text, tool calls, tool results,
         * images, thinking blocks, and system notifications. This sealed class hierarchy
         * enables type-safe handling of all content types.
         *
         * Use pattern matching (when expression) to handle specific content types.
         */
        @Serializable
        @JsonClassDiscriminator("type")
        sealed class ContentItem {
            /**
             * Plain text message from user.
             *
             * @property text message content
             */
            @Serializable
            @SerialName("Message")
            data class UserMessage(val text: String) : ContentItem()

            /**
             * Tool invocation request from AI.
             *
             * AI models can request tool execution (file operations, web searches, etc.).
             * Each tool call has unique ID for correlating with results.
             *
             * @property id unique tool call identifier (correlates with ToolResult.toolUseId)
             * @property call tool invocation data (name and JSON input parameters)
             */
            @Serializable
            data class ToolCall(
                val id: Id,
                val call: Data,
            ) : ContentItem() {
                @Serializable
                @JvmInline
                value class Id(val value: String)

                /**
                 * Tool invocation data.
                 *
                 * @property name tool name (e.g., "grz_read_file", "brave_web_search")
                 * @property input JSON object with tool-specific parameters
                 */
                @Serializable
                data class Data(
                    val name: String,
                    val input: JsonElement
                )
            }

            /**
             * Tool execution result.
             *
             * Contains output from tool execution, correlated to original ToolCall via toolUseId.
             * Results can be text, binary data (base64), URLs, or file references.
             * Multiple result items support tools that return structured multi-part output.
             *
             * @property toolUseId correlates with ToolCall.id
             * @property toolName tool that was executed
             * @property result list of result data items (text, base64, URL, file)
             * @property isError true if tool execution failed, false for success
             */
            @Serializable
            data class ToolResult(
                val toolUseId: ToolCall.Id,
                val toolName: String,
                val result: List<Data>,
                val isError: Boolean = false,
            ) : ContentItem() {
                /**
                 * Polymorphic tool result data.
                 *
                 * Supports multiple output formats: plain text, binary data (base64-encoded),
                 * URLs, and file references. Use pattern matching to handle specific types.
                 */
                @Serializable
                sealed class Data {
                    /**
                     * Plain text result.
                     *
                     * @property content text output from tool
                     */
                    @Serializable
                    data class Text(val content: String) : Data()

                    /**
                     * Binary data encoded as base64.
                     *
                     * @property data base64-encoded binary content
                     * @property mediaType MIME type of the data
                     */
                    @Serializable
                    data class Base64Data(
                        val data: String,
                        val mediaType: MediaType,
                    ) : Data()

                    /**
                     * URL reference to external resource.
                     *
                     * @property url resource location
                     * @property mediaType optional MIME type hint
                     */
                    @Serializable
                    data class UrlData(
                        val url: String,
                        val mediaType: MediaType? = null,
                    ) : Data()

                    /**
                     * File reference by ID.
                     *
                     * @property fileId file identifier in storage system
                     * @property mediaType optional MIME type hint
                     */
                    @Serializable
                    data class FileData(
                        val fileId: String,
                        val mediaType: MediaType? = null,
                    ) : Data()
                }
            }

            /**
             * Extended thinking block from LLM.
             *
             * Some AI models (e.g., Claude with extended thinking mode) output internal
             * reasoning before generating final response. These blocks are cryptographically
             * signed by the model to prevent tampering and ensure authenticity.
             *
             * @property signature cryptographic signature from LLM (verifies block authenticity)
             * @property thinking reasoning content from the model
             */
            @Serializable
            data class Thinking(
                val signature: String,
                val thinking: String,
            ) : ContentItem()

            /**
             * System notification or status message.
             *
             * Used for UI notifications, errors, warnings, and tool execution status.
             * Can be correlated with tool calls via optional toolUseId.
             *
             * @property level severity (INFO, WARNING, ERROR)
             * @property content notification text
             * @property toolUseId optional tool call correlation (for tool-related notifications)
             */
            @Serializable
            data class System(
                val level: SystemLevel,
                val content: String,
                val toolUseId: ToolCall.Id? = null,
            ) : ContentItem() {
                /**
                 * System message severity level.
                 */
                @Serializable
                enum class SystemLevel {
                    INFO, WARNING, ERROR
                }
            }

            /**
             * Intermediate assistant response with structured text.
             *
             * AI responses may include structured text with separate TTS content
             * and voice tone hints for text-to-speech rendering.
             *
             * @property structured structured text with optional TTS variants
             */
            @Serializable
            @SerialName("IntermediateMessage")
            data class AssistantMessage(
                val structured: StructuredText,
            ) : ContentItem()

            /**
             * Image content item.
             *
             * Images can be embedded (base64), referenced by URL, or stored as files.
             * Use ImageSource variants to handle different storage methods.
             *
             * @property source image data source (base64, URL, or file reference)
             */
            @Serializable
            data class ImageItem(
                val source: ImageSource,
            ) : ContentItem()

            /**
             * Unknown or unparsable JSON content.
             *
             * Fallback for content types not recognized by current parser.
             * Preserves raw JSON for future compatibility or debugging.
             *
             * @property json raw JSON element
             */
            @Serializable
            data class UnknownJson(
                val json: JsonElement,
            ) : ContentItem()
        }

        /**
         * Structured text with optional TTS (text-to-speech) variants.
         *
         * AI responses may provide separate content for display vs. speech,
         * enabling better TTS rendering with different wording or voice tone.
         *
         * @property fullText complete text for display
         * @property ttsText optional simplified text optimized for text-to-speech (null uses fullText)
         * @property voiceTone optional voice tone hint for TTS (e.g., "cheerful", "serious")
         * @property failedToParse true if structured parsing failed, false if successfully parsed
         */
        @Serializable
        data class StructuredText(
            val fullText: String,
            val ttsText: String? = null,
            val voiceTone: String? = null,
            val failedToParse: Boolean = false,
        )

        /**
         * Polymorphic image source.
         *
         * Images can be stored in multiple ways: embedded as base64, referenced by URL,
         * or stored as files. Use pattern matching to handle specific storage methods.
         */
        @Serializable
        @JsonClassDiscriminator("type")
        sealed class ImageSource {
            abstract val type: String

            /**
             * Base64-encoded image data.
             *
             * @property data base64-encoded image content
             * @property mediaType MIME type (e.g., "image/png", "image/jpeg")
             * @property type discriminator value ("base64")
             */
            @Serializable
            @SerialName("base64")
            data class Base64ImageSource(
                val data: String,
                @SerialName("media_type")
                val mediaType: String,
                override val type: String = "base64",
            ) : ImageSource()

            /**
             * URL-referenced image.
             *
             * @property url image location
             * @property type discriminator value ("url")
             */
            @Serializable
            @SerialName("url")
            data class UrlImageSource(
                val url: String,
                override val type: String = "url",
            ) : ImageSource()

            /**
             * File-stored image reference.
             *
             * @property fileId file identifier in storage system
             * @property type discriminator value ("file")
             */
            @Serializable
            @SerialName("file")
            data class FileImageSource(
                @SerialName("file_id")
                val fileId: String,
                override val type: String = "file",
            ) : ImageSource()
        }

        /**
         * MIME media type.
         *
         * Represents media type in format "type/subtype" (e.g., "image/png", "application/json").
         * Provides common constants and parsing from string format.
         *
         * @property type primary type (e.g., "image", "text", "application")
         * @property subtype specific subtype (e.g., "png", "plain", "json")
         */
        @Serializable
        data class MediaType(
            val type: String,
            val subtype: String,
        ) {
            val value: String get() = "$type/$subtype"

            companion object {
                val IMAGE_PNG = MediaType("image", "png")
                val IMAGE_JPEG = MediaType("image", "jpeg")
                val TEXT_PLAIN = MediaType("text", "plain")
                val APPLICATION_JSON = MediaType("application", "json")

                /**
                 * Parses MIME type from string format.
                 *
                 * @param value MIME type string (e.g., "image/png")
                 * @return parsed MediaType, or "application/octet-stream" if invalid format
                 */
                fun parse(value: String): MediaType {
                    val parts = value.split("/", limit = 2)
                    return if (parts.size == 2) {
                        MediaType(parts[0], parts[1])
                    } else {
                        MediaType("application", "octet-stream")
                    }
                }
            }
        }

        /**
         * Metadata instruction for message processing.
         *
         * Instructions control message routing, response expectations, and source tracking
         * in multi-agent conversations. They are metadata that doesn't appear in message
         * content but affects how messages are processed.
         */
        @Serializable
        sealed class Instruction {
            abstract val title: String
            abstract val description: String
            abstract fun serializeContent(): String
            abstract fun toXmlLine(): String

            /**
             * User-defined custom instruction.
             *
             * @property id unique instruction identifier
             * @property title instruction name
             * @property description instruction details
             */
            @Serializable
            data class UserInstruction(
                val id: String,
                override val title: String,
                override val description: String,
            ) : Instruction() {
                override fun serializeContent() = "$id:$title:$description"
                override fun toXmlLine() = "<user-instruction>${serializeContent()}</user-instruction>"
            }

            /**
             * Response routing instruction for multi-agent communication.
             *
             * Indicates that sender expects a response from the receiving agent,
             * specifying which agent should receive the reply via targetTabId.
             *
             * @property targetTabId tab ID of agent that should receive response
             */
            @Serializable
            data class ResponseExpected(
                val targetTabId: String
            ) : Instruction() {
                override val title = "Response Expected"
                override val description = "Use mcp__gromozeka__tell_agent with target_tab_id: $targetTabId"
                override fun serializeContent() = "response_expected:$targetTabId"
                override fun toXmlLine() = "<instruction>response_expected:${title}:${description}</instruction>"
            }

            /**
             * Message source tracking.
             *
             * Identifies whether message originated from user or another agent.
             * Used for routing responses and understanding conversation flow.
             */
            @Serializable
            sealed class Source : Instruction() {
                /**
                 * Message from human user.
                 */
                @Serializable
                @SerialName("user")
                object User : Source() {
                    override val title = "User"
                    override val description = "Message from user"
                    override fun serializeContent() = "user"
                    override fun toXmlLine() = "<source>user</source>"
                }

                /**
                 * Message from another AI agent.
                 *
                 * @property tabId source agent's tab identifier
                 */
                @Serializable
                @SerialName("agent")
                data class Agent(val tabId: String) : Source() {
                    override val title = "Agent"
                    override val description = "Message from agent (Tab ID: $tabId)"
                    override fun serializeContent() = "agent:$tabId"
                    override fun toXmlLine() = "<source>agent:$tabId</source>"
                }
            }
        }
    }

    /**
     * Immutable record of a squash operation.
     *
     * Squash operations use AI to summarize, restructure, or distill multiple messages
     * into a single consolidated message. This is NOT simple concatenation - the AI
     * may rewrite, reorganize, or extract key points.
     *
     * Squash operations are tracked for:
     * - Reproducibility: Can reproduce squash with same prompt/model
     * - Audit trail: Know what was squashed and when
     * - Undo support: Can restore original messages if needed
     *
     * Source messages can be non-contiguous (e.g., squashing messages 1, 5, 7).
     *
     * This is an immutable record - squash operations cannot be modified after creation.
     *
     * @property id unique squash operation identifier
     * @property conversationId conversation this squash belongs to
     * @property sourceMessageIds messages that were squashed (can be non-contiguous)
     * @property resultMessageId consolidated message created by squash
     * @property prompt optional AI prompt used for squashing (for reproducibility)
     * @property model optional AI model used (for reproducibility)
     * @property performedByAgent true if AI performed squash, false if manual/concatenation
     * @property createdAt timestamp when squash was performed (immutable)
     */
    @Serializable
    data class SquashOperation(
        val id: Id,
        val conversationId: Conversation.Id,
        val sourceMessageIds: List<Message.Id>,
        val resultMessageId: Message.Id,
        val prompt: String? = null,
        val model: String? = null,
        val performedByAgent: Boolean,
        val createdAt: Instant,
    ) {
        @Serializable
        @JvmInline
        value class Id(val value: String)
    }
}
