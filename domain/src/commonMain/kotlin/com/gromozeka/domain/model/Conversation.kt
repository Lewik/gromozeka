@file:kotlinx.serialization.UseSerializers(
    com.gromozeka.domain.model.serialization.JsonElementTransportSerializer::class,
    com.gromozeka.domain.model.serialization.JsonObjectTransportSerializer::class,
)

package com.gromozeka.domain.model

import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.jvm.JvmInline

/**
 * Conversation containing threads and messages.
 *
 * Conversation is the top-level organizational unit for human and AI interaction.
 * Each conversation belongs to a project and contains multiple threads (versions).
 *
 * This is an immutable value type - use copy() to create modified versions.
 *
 * @property id unique conversation identifier (UUIDv7)
 * @property projectId project this conversation belongs to
 * @property participants users and agents currently connected to this conversation
 * @property displayName human-readable conversation title (can be blank)
 * @property currentThread currently active thread ID (conversation can switch threads)
 * @property createdAt timestamp when conversation was created
 * @property updatedAt timestamp of last conversation activity
 */
@Serializable
data class Conversation(
    val id: Id,
    val projectId: Project.Id,
    val participants: Set<Participant>,
    val displayName: String = "",
    val currentThread: Thread.Id,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    init {
        require(participants.any { it is Participant.User }) {
            "Conversation must have at least one user participant"
        }
    }

    /**
     * Unique conversation identifier (UUIDv7).
     */
    @Serializable
    @JvmInline
    value class Id(val value: String)

    @Serializable
    @JsonClassDiscriminator("type")
    sealed interface Participant {
        @Serializable
        @SerialName("user")
        data class User(
            val userId: com.gromozeka.domain.model.User.Id,
        ) : Participant

        @Serializable
        @SerialName("agent")
        data class Agent(
            val agentDefinitionId: AgentDefinition.Id,
        ) : Participant
    }

    @Serializable
    enum class TurnTerminationReason {
        STOPPED,
        INTERRUPTED,
    }

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
     * Messages can reference other messages for edit/version and reply provenance.
     *
     * This is an immutable value type - use copy() to create modified versions.
     *
     * @property id unique message identifier (UUIDv7)
     * @property conversationId parent conversation
     * @property originalIds messages this immutable version was derived from
     * @property replyTo if not null, this message is a response to another message
     * @property role provider-facing message role (USER, ASSISTANT, SYSTEM)
     * @property author actor that created the message, when known
     * @property content list of content items (text, tool calls, images, thinking blocks, etc.)
     * @property instructions list of instructions attached to this message (user instructions, response expected tags, source metadata)
     * @property providerMetadata provider-specific metadata preserved for replay/debugging
     * @property createdAt timestamp when message was created
     */
    @Serializable
    data class Message(
        val id: Id,
        val conversationId: Conversation.Id,

        val originalIds: List<Id> = emptyList(),
        val replyTo: Id? = null,

        val role: Role,
        val author: Author? = null,
        val content: List<ContentItem>,
        val instructions: List<Instruction> = emptyList(),
        val providerMetadata: JsonObject = JsonObject(emptyMap()),

        val createdAt: Instant,

        val error: GenerationError? = null,
    ) {
        /**
         * Error that occurred during message generation.
         *
         * @property message human-readable error description
         * @property type error category (e.g., "network", "api", "cancelled", "timeout")
         */
        @Serializable
        data class GenerationError(
            val message: String,
            val type: String? = null,
        )

        /**
         * Unique message identifier (UUIDv7).
         */
        @Serializable
        @JvmInline
        value class Id(val value: String)

        /**
         * Provider-facing message role.
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

        @Serializable
        @JsonClassDiscriminator("type")
        sealed class Author {
            abstract val displayName: String

            @Serializable
            @SerialName("user")
            data class User(
                val userId: com.gromozeka.domain.model.User.Id,
                override val displayName: String,
            ) : Author()

            @Serializable
            @SerialName("agent")
            data class Agent(
                val agentDefinitionId: AgentDefinition.Id,
                override val displayName: String,
            ) : Author()
        }

        /**
         * Block streaming state.
         */
        @Serializable
        enum class BlockState {
            /** Block is currently being streamed */
            STREAMING,
            /** Block streaming was interrupted by user */
            INTERRUPTED,
            /** Block streaming completed successfully */
            COMPLETE
        }

        /**
         * Content item within message.
         *
         * Message can contain multiple content items of different types
         * (text, tool calls, tool results, images, thinking blocks, system notifications).
         *
         * @property state streaming state of this block
         */
        @Serializable
        @JsonClassDiscriminator("type")
        sealed class ContentItem {
            abstract val state: BlockState

            /**
             * User text message.
             *
             * @property text message content
             */
            @Serializable
            @SerialName("Message")
            data class UserMessage(
                val text: String,
                override val state: BlockState = BlockState.COMPLETE
            ) : ContentItem()

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
                override val state: BlockState = BlockState.COMPLETE
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
             * @property executionToolName immutable executor-facing name when it differs from [toolName]
             */
            @Serializable
            data class ToolResult(
                val toolUseId: ToolCall.Id,
                val toolName: String,
                val result: List<Data>,
                val isError: Boolean = false,
                override val state: BlockState = BlockState.COMPLETE,
                val executionToolName: String? = null,
            ) : ContentItem() {
                /**
                 * Tool result data item.
                 */
                @Serializable
                @JsonClassDiscriminator("type")
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
                        val fileName: String? = null,
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
                    data class ArtifactData(val artifact: Artifact.Reference) : Data()
                }
            }

            /**
             * Extended thinking block from LLM.
             *
             * Some LLMs support extended thinking mode where they reason step-by-step
             * before providing final answer. Thinking blocks are cryptographically signed
             * to ensure authenticity and prevent tampering.
             *
             * @property thinking actual thinking content (step-by-step reasoning)
             * @property signature cryptographic signature from LLM (null during streaming, set on block complete)
             */
            @Serializable
            data class Thinking(
                val thinking: String,
                val signature: String? = null,
                override val state: BlockState = BlockState.COMPLETE
            ) : ContentItem() {
                val isVisible: Boolean
                    get() = thinking.isNotBlank()
            }

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
                override val state: BlockState = BlockState.COMPLETE
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
                override val state: BlockState = BlockState.COMPLETE
            ) : ContentItem()

            /**
             * Image content.
             *
             * @property source image source (base64, URL, or file reference)
             */
            @Serializable
            data class ImageItem(
                val source: ImageSource,
                override val state: BlockState = BlockState.COMPLETE
            ) : ContentItem()

            @Serializable
            data class DocumentItem(
                val source: DocumentSource,
                override val state: BlockState = BlockState.COMPLETE,
            ) : ContentItem()

            @Serializable
            data class ArtifactItem(
                val artifact: Artifact.Reference,
                override val state: BlockState = BlockState.COMPLETE,
            ) : ContentItem()

            @Serializable
            @SerialName("ContextCompactionResult")
            data class ContextCompactionResult(
                val payload: Payload,
                val origin: Origin,
                val strategy: Strategy = Strategy.UNKNOWN,
                val sourceMessageIds: List<Id> = emptyList(),
                val providerScope: ProviderScope? = null,
                val promptTemplate: PromptTemplateReference? = null,
                override val state: BlockState = BlockState.COMPLETE,
            ) : ContentItem() {
                @Serializable
                enum class Origin {
                    USER_REQUESTED,
                    GROMOZEKA_POLICY,
                    PROVIDER_AUTO,
                    RUNTIME_MIGRATION,
                }

                @Serializable
                enum class Strategy {
                    CONCATENATE,
                    SUMMARIZE,
                    DISTILL,
                    PROVIDER_MANAGED,
                    UNKNOWN,
                }

                @Serializable
                data class ProviderScope(
                    val provider: String,
                    val connectionId: String? = null,
                    val modelConfigurationId: String? = null,
                    val modelName: String? = null,
                )

                @Serializable
                data class PromptTemplateReference(
                    val id: String,
                    val version: Int,
                ) {
                    init {
                        require(id.isNotBlank()) { "Prompt template id must not be blank" }
                        require(version > 0) { "Prompt template version must be positive" }
                    }
                }

                @Serializable
                @JsonClassDiscriminator("kind")
                sealed class Payload {
                    @Serializable
                    @SerialName("readable_summary")
                    data class ReadableSummary(
                        val text: String,
                    ) : Payload()

                    @Serializable
                    @SerialName("opaque_provider_state")
                    data class OpaqueProviderState(
                        val state: JsonObject,
                    ) : Payload()
                }
            }

            /**
             * Unknown JSON content (fallback for forward compatibility).
             *
             * @property json raw JSON element
             */
            @Serializable
            data class UnknownJson(
                val json: JsonElement,
                override val state: BlockState = BlockState.COMPLETE
            ) : ContentItem()
        }

        /**
         * Structured text with optional TTS metadata.
         *
         * @property fullText complete message text
         * @property ttsText optional text optimized for text-to-speech (stripped markdown, etc.)
         * @property voiceTone optional voice tone hint for TTS
         * @property attentionRequested true when the user should look at this response now
         * @property suggestedReplies concise user replies offered as composer shortcuts
         * @property failedToParse true if structured parsing failed, fullText contains raw response
         */
        @Serializable
        data class StructuredText(
            val fullText: String,
            val ttsText: String? = null,
            val voiceTone: String? = null,
            val attentionRequested: Boolean = false,
            val suggestedReplies: List<String> = emptyList(),
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

        @Serializable
        @JsonClassDiscriminator("type")
        sealed class DocumentSource {
            abstract val type: String

            @Serializable
            @SerialName("base64")
            data class Base64DocumentSource(
                val data: String,
                @SerialName("media_type")
                val mediaType: String,
                @SerialName("file_name")
                val fileName: String,
                override val type: String = "base64",
            ) : DocumentSource()
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
        @JsonClassDiscriminator("type")
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

            @Serializable
            @SerialName("message_input_context")
            data class MessageInputRuntimeContext(
                val context: MessageInputContext,
            ) : Instruction() {
                override val title = "Message input context"
                override val description = "How this user message entered Gromozeka"
                override fun serializeContent() = context.toXml()
                override fun toXmlLine() = serializeContent()
            }

            @Serializable
            @SerialName("message_temporal_context")
            data class MessageTemporalRuntimeContext(
                val context: MessageTemporalContext,
            ) : Instruction() {
                override val title = "Message time"
                override val description = "When this user message was sent"
                override fun serializeContent() = context.toXml()
                override fun toXmlLine() = serializeContent()
            }

            @Serializable
            @SerialName("revealed_secret_context")
            data class RevealedSecretRuntimeContext(
                val context: com.gromozeka.domain.model.RevealedSecretRuntimeContext,
            ) : Instruction() {
                override val title = "Revealed secrets"
                override val description = "Secrets explicitly approved by the user for this model request"
                override fun serializeContent() = context.toXml()
                override fun toXmlLine() = serializeContent()
            }

            @Serializable
            @SerialName("user_situation_context")
            data class UserSituationRuntimeContext(
                val context: UserSituationContext,
            ) : Instruction() {
                override val title = "User situation context"
                override val description = "Runtime facts about the user's current situation"
                override fun serializeContent() = context.toXml()
                override fun toXmlLine() = serializeContent()
            }

            @Serializable
            @SerialName("previous_turn_terminated")
            data class PreviousTurnTerminated(
                val turnId: String,
                val reason: TurnTerminationReason,
                val occurredAt: Instant,
            ) : Instruction() {
                override val title = when (reason) {
                    TurnTerminationReason.STOPPED -> "Previous turn stopped"
                    TurnTerminationReason.INTERRUPTED -> "Previous turn interrupted"
                }
                override val description = when (reason) {
                    TurnTerminationReason.STOPPED ->
                        "The user deliberately stopped the previous turn before normal completion"
                    TurnTerminationReason.INTERRUPTED ->
                        "The user deliberately interrupted the previous turn before normal completion"
                }

                override fun serializeContent(): String = when (reason) {
                    TurnTerminationReason.STOPPED -> """
                        <turn_aborted reason="user_stopped">
                        The user stopped the previous turn on purpose before it reached normal completion. The operation already in flight may have completed, but later continuation steps were not run. Do not assume unfinished work completed or retry it automatically; use the user's new message to decide what to do next.
                        </turn_aborted>
                    """.trimIndent()
                    TurnTerminationReason.INTERRUPTED -> """
                        <turn_aborted reason="user_interrupted">
                        The user interrupted the previous turn on purpose. In-flight operations may have been cancelled after partial execution, and background commands may still be running or stopping. Do not assume completion or retry automatically; inspect current state when relevant and follow the user's new message.
                        </turn_aborted>
                    """.trimIndent()
                }

                override fun toXmlLine() = serializeContent()
            }

            @Serializable
            @SerialName("workspace_context")
            data class WorkspaceContext(
                val references: List<WorkspaceContextReference>,
            ) : Instruction() {
                init {
                    require(references.isNotEmpty()) { "Workspace context references cannot be empty" }
                }

                override val title = "Workspace context"
                override val description = references.joinToString { it.name }
                override fun serializeContent(): String =
                    buildString {
                        appendLine(
                            "<workspace_context purpose=\"Paths explicitly selected by the user for this message; inspect them when relevant\">"
                        )
                        references.forEach { reference ->
                            append("  <reference kind=\"")
                            append(reference.kind.name.lowercase())
                            append("\" path=\"")
                            append(reference.relativePath.escapeXml())
                            append("\">")
                            append(reference.name.escapeXml())
                            appendLine("</reference>")
                        }
                        append("</workspace_context>")
                    }

                override fun toXmlLine() = serializeContent()
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
                override val description = "Use tell_agent with target_tab_id: $targetTabId"
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

}
