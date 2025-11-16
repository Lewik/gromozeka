package com.gromozeka.bot.domain.model

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonElement

/**
 * Conversation containing threads and messages.
 *
 * Conversation is the top-level organizational unit for AI interaction.
 * Each conversation belongs to a project and uses specific AI provider/model.
 * Conversation contains multiple threads (append-only message sequences).
 *
 * This is an immutable value type - use copy() to create modified versions.
 *
 * @property id unique conversation identifier (UUIDv7)
 * @property projectId project this conversation belongs to
 * @property displayName human-readable conversation title (can be blank)
 * @property aiProvider AI provider identifier (e.g., "CLAUDE", "GEMINI", "OPENAI")
 * @property modelName model identifier (e.g., "claude-3-5-sonnet-20241022")
 * @property currentThread currently active thread ID (conversation can switch threads)
 * @property createdAt timestamp when conversation was created
 * @property updatedAt timestamp of last modification
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
    /**
     * Unique conversation identifier (UUIDv7).
     */
    @Serializable
    @JvmInline
    value class Id(val value: String)

    /**
     * Conversation thread containing ordered message sequence.
     *
     * Thread is append-only by default. Explicit operations (delete, edit, squash)
     * create new derived thread with originalThread reference.
     *
     * This is an immutable value type - use copy() to create modified versions.
     *
     * @property id unique thread identifier (UUIDv7)
     * @property conversationId parent conversation
     * @property originalThread if not null, this thread was derived from another via explicit operation
     * @property lastTurnNumber highest turn number in this thread (user + assistant = 1 turn)
     * @property createdAt timestamp when thread was created
     * @property updatedAt timestamp of last message addition
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
        /**
         * Unique thread identifier (UUIDv7).
         */
        @Serializable
        @JvmInline
        value class Id(val value: String)
    }

    /**
     * Single message in conversation thread.
     *
     * Message represents one interaction unit (user input, assistant response, system notification).
     * Messages can reference other messages (replyTo, squashOperationId) for provenance tracking.
     *
     * This is an immutable value type - use copy() to create modified versions.
     *
     * @property id unique message identifier (UUIDv7)
     * @property conversationId parent conversation
     * @property originalIds DEPRECATED - use squashOperationId instead (will be removed after squash migration)
     * @property squashOperationId if not null, this message was created via AI-powered squash operation
     * @property replyTo if not null, this message is a response to another message
     * @property role message author (USER, ASSISTANT, SYSTEM)
     * @property content list of content items (text, tool calls, images, thinking blocks, etc.)
     * @property instructions list of instructions attached to this message (user instructions, response expected tags, source metadata)
     * @property createdAt timestamp when message was created
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
        /**
         * Unique message identifier (UUIDv7).
         */
        @Serializable
        @JvmInline
        value class Id(val value: String)

        /**
         * Message author role.
         */
        @Serializable
        enum class Role {
            /** Human user input */
            USER,

            /** AI assistant response */
            ASSISTANT,

            /** System notification or instruction */
            SYSTEM
        }

        /**
         * Content item within message.
         *
         * Message can contain multiple content items of different types
         * (text, tool calls, tool results, images, thinking blocks, system notifications).
         */
        @Serializable
        @JsonClassDiscriminator("type")
        sealed class ContentItem {
            /**
             * User text message.
             *
             * @property text message content
             */
            @Serializable
            @SerialName("Message")
            data class UserMessage(val text: String) : ContentItem()

            /**
             * Tool invocation request from AI.
             *
             * @property id unique tool call identifier (for matching with tool result)
             * @property call tool invocation data (name and input parameters)
             */
            @Serializable
            data class ToolCall(
                val id: Id,
                val call: Data,
            ) : ContentItem() {
                /**
                 * Unique tool call identifier (UUIDv7).
                 */
                @Serializable
                @JvmInline
                value class Id(val value: String)

                /**
                 * Tool invocation data.
                 *
                 * @property name tool name (e.g., "grz_read_file", "brave_web_search")
                 * @property input tool input parameters as JSON
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
             * @property toolUseId matches ToolCall.id that requested this result
             * @property toolName tool that was executed
             * @property result list of result data items (text, images, files)
             * @property isError true if tool execution failed
             */
            @Serializable
            data class ToolResult(
                val toolUseId: ToolCall.Id,
                val toolName: String,
                val result: List<Data>,
                val isError: Boolean = false,
            ) : ContentItem() {
                /**
                 * Tool result data item.
                 */
                @Serializable
                sealed class Data {
                    /**
                     * Text result.
                     *
                     * @property content text content
                     */
                    @Serializable
                    data class Text(val content: String) : Data()

                    /**
                     * Base64-encoded binary data.
                     *
                     * @property data base64-encoded content
                     * @property mediaType MIME type (e.g., image/png)
                     */
                    @Serializable
                    data class Base64Data(
                        val data: String,
                        val mediaType: MediaType,
                    ) : Data()

                    /**
                     * URL reference.
                     *
                     * @property url URL to resource
                     * @property mediaType optional MIME type hint
                     */
                    @Serializable
                    data class UrlData(
                        val url: String,
                        val mediaType: MediaType? = null,
                    ) : Data()

                    /**
                     * File reference.
                     *
                     * @property fileId file identifier in storage
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
             * Some LLMs support extended thinking mode where they reason step-by-step
             * before providing final answer. Thinking blocks are cryptographically signed
             * to ensure authenticity and prevent tampering.
             *
             * @property signature cryptographic signature from LLM (e.g., Claude signs thinking blocks)
             * @property thinking actual thinking content (step-by-step reasoning)
             */
            @Serializable
            data class Thinking(
                val signature: String,
                val thinking: String,
            ) : ContentItem()

            /**
             * System notification or message.
             *
             * @property level severity level (INFO, WARNING, ERROR)
             * @property content notification text
             * @property toolUseId if not null, this notification is related to specific tool call
             */
            @Serializable
            data class System(
                val level: SystemLevel,
                val content: String,
                val toolUseId: ToolCall.Id? = null,
            ) : ContentItem() {
                /**
                 * System notification severity level.
                 */
                @Serializable
                enum class SystemLevel {
                    INFO, WARNING, ERROR
                }
            }

            /**
             * Assistant text message with structured content.
             *
             * @property structured structured text with optional TTS metadata
             */
            @Serializable
            @SerialName("IntermediateMessage")
            data class AssistantMessage(
                val structured: StructuredText,
            ) : ContentItem()

            /**
             * Image content.
             *
             * @property source image source (base64, URL, or file reference)
             */
            @Serializable
            data class ImageItem(
                val source: ImageSource,
            ) : ContentItem()

            /**
             * Unknown JSON content (fallback for forward compatibility).
             *
             * @property json raw JSON element
             */
            @Serializable
            data class UnknownJson(
                val json: JsonElement,
            ) : ContentItem()
        }

        /**
         * Structured text with optional TTS metadata.
         *
         * @property fullText complete message text
         * @property ttsText optional text optimized for text-to-speech (stripped markdown, etc.)
         * @property voiceTone optional voice tone hint for TTS
         * @property failedToParse true if structured parsing failed, fullText contains raw response
         */
        @Serializable
        data class StructuredText(
            val fullText: String,
            val ttsText: String? = null,
            val voiceTone: String? = null,
            val failedToParse: Boolean = false,
        )

        /**
         * Image source reference.
         */
        @Serializable
        @JsonClassDiscriminator("type")
        sealed class ImageSource {
            abstract val type: String

            /**
             * Base64-encoded image.
             *
             * @property data base64-encoded image content
             * @property mediaType MIME type (e.g., image/png, image/jpeg)
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
             * Image URL reference.
             *
             * @property url image URL
             * @property type discriminator value ("url")
             */
            @Serializable
            @SerialName("url")
            data class UrlImageSource(
                val url: String,
                override val type: String = "url",
            ) : ImageSource()

            /**
             * Image file reference.
             *
             * @property fileId file identifier in storage
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
         * @property type primary type (e.g., "image", "text", "application")
         * @property subtype subtype (e.g., "png", "plain", "json")
         */
        @Serializable
        data class MediaType(
            val type: String,
            val subtype: String,
        ) {
            /**
             * Full MIME type string (e.g., "image/png").
             */
            val value: String get() = "$type/$subtype"

            companion object {
                val IMAGE_PNG = MediaType("image", "png")
                val IMAGE_JPEG = MediaType("image", "jpeg")
                val TEXT_PLAIN = MediaType("text", "plain")
                val APPLICATION_JSON = MediaType("application", "json")

                /**
                 * Parses MIME type string.
                 *
                 * @param value MIME type string (e.g., "image/png")
                 * @return parsed MediaType or "application/octet-stream" if invalid
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
         * Instruction attached to message.
         *
         * Instructions modify message interpretation or expected behavior
         * (user instructions, response expectations, source metadata).
         */
        @Serializable
        sealed class Instruction {
            abstract val title: String
            abstract val description: String

            /**
             * Serializes instruction for persistence.
             */
            abstract fun serializeContent(): String

            /**
             * Converts instruction to XML line for LLM prompt.
             */
            abstract fun toXmlLine(): String

            /**
             * User-defined instruction.
             *
             * @property id instruction identifier
             * @property title instruction title
             * @property description instruction description
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
             * Response expected instruction for inter-agent communication.
             *
             * Tells agent that another agent expects response back via tell_agent.
             *
             * @property targetTabId tab ID to send response to
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
             * Message source metadata.
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
                 * Message from another agent.
                 *
                 * @property tabId source agent's tab ID
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
     * Immutable record of squash operation.
     *
     * Squash operation is AI-powered message summarization/restructuring.
     * Tracks provenance for reproducibility and audit trail.
     *
     * This is an immutable value type - use copy() to create modified versions.
     *
     * @property id unique squash operation identifier (UUIDv7)
     * @property conversationId conversation this operation belongs to
     * @property sourceMessageIds original messages that were squashed
     * @property resultMessageId resulting squashed message
     * @property prompt optional AI prompt used for squashing
     * @property model optional AI model used for squashing
     * @property performedByAgent true if AI performed squash, false if manual/concatenation
     * @property createdAt timestamp when squash was performed
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
        /**
         * Unique squash operation identifier (UUIDv7).
         */
        @Serializable
        @JvmInline
        value class Id(val value: String)
    }
}
