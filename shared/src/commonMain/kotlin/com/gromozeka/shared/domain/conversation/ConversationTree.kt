package com.gromozeka.shared.domain.conversation

import com.gromozeka.shared.domain.project.Project
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonElement
import kotlin.time.Instant

/**
 * ConversationTree - DAG всех сообщений в разговоре с AI
 *
 * Архитектурные решения:
 * - Убран agentId (разговор не привязан к агенту, можно продолжить с другим)
 * - Добавлен displayName (имя разговора, раньше было в Thread)
 * - Добавлена поддержка fork через parentConversation + branchFromMessage
 * - Добавлена навигация через head + branchSelections (раньше было в Thread)
 * - Thread как сущность удален (избыточная обертка)
 */
@Serializable
data class ConversationTree(
    val id: Id,
    val projectId: Project.Id,
    val displayName: String? = null,

    // Fork support - параллельные разговоры в разных ветках
    val parentConversation: Id? = null,
    val branchFromMessage: Message.Id? = null,

    // DAG структура
    val messages: List<Message> = emptyList(),

    // Navigation - определяет текущий путь через DAG
    val head: Message.Id? = null,
    val branchSelections: Set<Message.Id> = emptySet(),

    val tags: Set<String> = emptySet(),
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    @Serializable
    @JvmInline
    value class Id(val value: String)

    /**
     * Message - узел в DAG разговора
     */
    @Serializable
    data class Message(
        val id: Id,

        // DAG structure - может быть несколько родителей (для merge)
        val parentIds: Set<Id> = emptySet(),

        // Content
        val role: Role,
        val content: List<ContentItem>,
        val timestamp: Instant,

        // Context
        val instructions: List<Instruction> = emptyList(),
    ) {
        @Serializable
        @JvmInline
        value class Id(val value: String)

        @Serializable
        enum class Role {
            USER, ASSISTANT, SYSTEM
        }

        /**
         * ContentItem hierarchy
         */
        @Serializable
        @JsonClassDiscriminator("type")
        sealed class ContentItem {
            @Serializable
            @SerialName("Message")
            data class UserMessage(val text: String) : ContentItem()

            @Serializable
            data class ToolCall(
                val id: Id,
                val call: Data,
            ) : ContentItem() {
                @Serializable
                @JvmInline
                value class Id(val value: String)

                @Serializable
                data class Data(
                    val name: String,
                    val input: JsonElement
                )
            }

            @Serializable
            data class ToolResult(
                val toolUseId: ToolCall.Id,
                val toolName: String,
                val result: List<Data>,
                val isError: Boolean = false,
            ) : ContentItem() {
                @Serializable
                sealed class Data {
                    @Serializable
                    data class Text(val content: String) : Data()

                    @Serializable
                    data class Base64Data(
                        val data: String,
                        val mediaType: MediaType,
                    ) : Data()

                    @Serializable
                    data class UrlData(
                        val url: String,
                        val mediaType: MediaType? = null,
                    ) : Data()

                    @Serializable
                    data class FileData(
                        val fileId: String,
                        val mediaType: MediaType? = null,
                    ) : Data()
                }
            }

            @Serializable
            data class Thinking(
                val signature: String,
                val thinking: String,
            ) : ContentItem()

            @Serializable
            data class System(
                val level: SystemLevel,
                val content: String,
                val toolUseId: ToolCall.Id? = null,
            ) : ContentItem() {
                @Serializable
                enum class SystemLevel {
                    INFO, WARNING, ERROR
                }
            }

            @Serializable
            @SerialName("IntermediateMessage")
            data class AssistantMessage(
                val structured: StructuredText,
            ) : ContentItem()

            @Serializable
            data class ImageItem(
                val source: ImageSource,
            ) : ContentItem()

            @Serializable
            data class UnknownJson(
                val json: JsonElement,
            ) : ContentItem()
        }

        @Serializable
        data class StructuredText(
            val fullText: String,
            val ttsText: String? = null,
            val voiceTone: String? = null,
            val failedToParse: Boolean = false,
        )

        @Serializable
        @JsonClassDiscriminator("type")
        sealed class ImageSource {
            abstract val type: String

            @Serializable
            @SerialName("base64")
            data class Base64ImageSource(
                val data: String,
                @SerialName("media_type")
                val mediaType: String,
                override val type: String = "base64",
            ) : ImageSource()

            @Serializable
            @SerialName("url")
            data class UrlImageSource(
                val url: String,
                override val type: String = "url",
            ) : ImageSource()

            @Serializable
            @SerialName("file")
            data class FileImageSource(
                @SerialName("file_id")
                val fileId: String,
                override val type: String = "file",
            ) : ImageSource()
        }

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

        @Serializable
        sealed class Instruction {
            abstract val title: String
            abstract val description: String
            abstract fun serializeContent(): String
            abstract fun toXmlLine(): String

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
            data class ResponseExpected(
                val targetTabId: String
            ) : Instruction() {
                override val title = "Response Expected"
                override val description = "Use mcp__gromozeka__tell_agent with target_tab_id: $targetTabId"
                override fun serializeContent() = "response_expected:$targetTabId"
                override fun toXmlLine() = "<instruction>response_expected:${title}:${description}</instruction>"
            }

            @Serializable
            sealed class Source : Instruction() {
                @Serializable
                @SerialName("user")
                object User : Source() {
                    override val title = "User"
                    override val description = "Message from user"
                    override fun serializeContent() = "user"
                    override fun toXmlLine() = "<source>user</source>"
                }

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

