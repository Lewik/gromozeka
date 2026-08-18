package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.NamedSecret
import com.gromozeka.domain.model.RevealedSecretRuntimeContext
import com.gromozeka.domain.model.User
import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.domain.tool.AiToolDefinition
import com.gromozeka.domain.tool.PreloadedServerToolMetadata
import com.gromozeka.domain.tool.TOOL_CONTEXT_CONVERSATION_ID
import com.gromozeka.domain.tool.ToolExecutionContext
import com.gromozeka.domain.tool.requiredUserId
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service

@Service
class PendingSecretRevealService {
    private data class Key(val conversationId: Conversation.Id, val userId: User.Id)

    private val pending = ConcurrentHashMap<Key, Map<String, String>>()

    fun queue(conversationId: Conversation.Id, userId: User.Id, values: Map<String, String>) {
        pending.merge(Key(conversationId, userId), values, Map<String, String>::plus)
    }

    fun consume(
        conversationId: Conversation.Id,
        userId: User.Id?,
        messages: List<Conversation.Message>,
    ): List<Conversation.Message> {
        val owner = userId ?: return messages
        val values = pending.remove(Key(conversationId, owner)) ?: return messages
        val index = messages.indexOfLast { message ->
            message.role == Conversation.Message.Role.USER &&
                message.content.any { it is Conversation.Message.ContentItem.UserMessage }
        }
        check(index >= 0) { "Cannot reveal a secret without a user message" }
        return messages.toMutableList().also { enriched ->
            enriched[index] = enriched[index].copy(
                instructions = enriched[index].instructions +
                    Conversation.Message.Instruction.RevealedSecretRuntimeContext(
                        RevealedSecretRuntimeContext(values)
                    )
            )
        }
    }
}

@Component
class ListNamedSecretsToolCallback(
    private val namedSecretService: NamedSecretApplicationService,
) : AiToolCallback {
    override val metadata = PreloadedServerToolMetadata
    override val definition = AiToolDefinition(
        name = "grz_list_secrets",
        description = "List the current user's durable named secrets without revealing their values. Use returned secret:// references in tool arguments or grz_execute_command.secret_environment.",
        inputSchema = """{"type":"object","additionalProperties":false,"properties":{}}""",
    )

    override fun call(toolInput: String, context: ToolExecutionContext?): String = runBlocking {
        buildJsonArray {
            namedSecretService.list(context.requiredUserId()).forEach { secret ->
                add(buildJsonObject {
                    put("name", secret.name)
                    put("description", secret.description)
                    put("reference", secret.reference)
                    put("updated_at", secret.updatedAt.toString())
                })
            }
        }.toString()
    }
}

@Component
class RevealNamedSecretToolCallback(
    private val namedSecretService: NamedSecretApplicationService,
    private val pendingSecretRevealService: PendingSecretRevealService,
) : AiToolCallback {
    @Serializable
    private data class Input(
        val name: String,
        val user_confirmed: Boolean,
    )

    private val json = Json { ignoreUnknownKeys = true }

    override val metadata = PreloadedServerToolMetadata
    override val definition = AiToolDefinition(
        name = "grz_reveal_secret",
        description = "Reveal one named secret to the model for the next model request only. Ask the user for explicit confirmation immediately before calling this tool. Never call it merely to pass a secret to another tool; use secret:// references for that.",
        inputSchema = """
            {
              "type":"object",
              "additionalProperties":false,
              "properties":{
                "name":{"type":"string","description":"Named secret name or exact secret:// reference."},
                "user_confirmed":{"type":"boolean","description":"True only after the user explicitly confirmed revealing this secret to the model."}
              },
              "required":["name","user_confirmed"]
            }
        """.trimIndent(),
    )

    override fun call(toolInput: String, context: ToolExecutionContext?): String = runBlocking {
        val input = json.decodeFromString<Input>(toolInput)
        require(input.user_confirmed) { "Explicit user confirmation is required" }
        val name = NamedSecret.nameFromReference(input.name) ?: NamedSecret.normalizeName(input.name)
        val userId = context.requiredUserId()
        val value = namedSecretService.resolve(userId, setOf(name)).getValue(name)
        val conversationId = Conversation.Id(
            context?.getString(TOOL_CONTEXT_CONVERSATION_ID)
                ?: error("Conversation id is required to reveal a secret")
        )
        pendingSecretRevealService.queue(conversationId, userId, mapOf(name to value))
        buildJsonObject {
            put("revealed", true)
            put("name", name)
            put("delivery", "next_model_request")
        }.toString()
    }
}
