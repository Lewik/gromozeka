package com.gromozeka.server

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ProjectPermission
import com.gromozeka.domain.service.ConversationDomainService
import com.gromozeka.domain.service.UserDirectoryService
import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.domain.tool.AiToolCallbackContributor
import com.gromozeka.domain.tool.AiToolDefinition
import com.gromozeka.domain.tool.AiToolExecutionScope
import com.gromozeka.domain.tool.AiToolLoadingPolicy
import com.gromozeka.domain.tool.AiToolMetadata
import com.gromozeka.domain.tool.TOOL_CONTEXT_CONVERSATION_ID
import com.gromozeka.domain.tool.ToolExecutionContext
import com.gromozeka.domain.tool.requiredUserId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.springframework.stereotype.Service

@Service
internal class ConversationToolContributor(
    private val conversationService: ConversationDomainService,
    private val userDirectoryService: UserDirectoryService,
    private val authorization: GromozekaRemoteAuthorization,
) : AiToolCallbackContributor {
    override val callbacks: List<AiToolCallback> = listOf(RenameCurrentConversationTool())

    private inner class RenameCurrentConversationTool : AiToolCallback {
        override val definition = AiToolDefinition(
            name = "grz_conversation_rename",
            description = "Rename the current conversation. The title should be concise and reflect the conversation's purpose.",
            inputSchema = RENAME_CONVERSATION_SCHEMA,
        )
        override val metadata = AiToolMetadata(
            executionScope = AiToolExecutionScope.SERVER,
            loadingPolicy = AiToolLoadingPolicy.PRELOAD_WHEN_AVAILABLE,
            visibleToMemoryPipeline = false,
        )

        override fun call(toolInput: String, context: ToolExecutionContext?): String = runBlocking {
            val input = conversationToolJson.decodeFromString<RenameConversationInput>(toolInput)
            val displayName = input.displayName.trim()
            require(displayName.isNotEmpty()) { "Conversation display name must not be blank" }

            val conversationId = context.requiredConversationId()
            val user = userDirectoryService.findActiveById(context.requiredUserId())
                ?: error("Conversation tools require an active authenticated user")
            authorization.requireConversation(user, conversationId, ProjectPermission.WRITE)
            val conversation = conversationService.updateDisplayName(conversationId, displayName)
                ?: error("Conversation unavailable")

            buildJsonObject {
                put("conversation_id", conversation.id.value)
                put("display_name", conversation.displayName)
            }.toString()
        }
    }

    private fun ToolExecutionContext?.requiredConversationId(): Conversation.Id =
        this?.getString(TOOL_CONTEXT_CONVERSATION_ID)
            ?.takeIf(String::isNotBlank)
            ?.let(Conversation::Id)
            ?: error("Conversation id is required in tool execution context")

    private companion object {
        const val RENAME_CONVERSATION_SCHEMA =
            """{"type":"object","properties":{"display_name":{"type":"string","minLength":1,"maxLength":255}},"required":["display_name"],"additionalProperties":false}"""
    }
}

@Serializable
private data class RenameConversationInput(
    @SerialName("display_name")
    val displayName: String,
)

private val conversationToolJson = Json {
    ignoreUnknownKeys = false
}
