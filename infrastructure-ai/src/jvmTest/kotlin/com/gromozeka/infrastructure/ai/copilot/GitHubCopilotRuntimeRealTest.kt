package com.gromozeka.infrastructure.ai.copilot

import com.gromozeka.domain.model.AppMode
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.UserDeviceSettings
import com.gromozeka.domain.model.UserProfile
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiExecutionTarget
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiRuntimeOptions
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiToolChoice
import com.gromozeka.domain.repository.AiUserCredentialRepository
import com.gromozeka.domain.service.SettingsProvider
import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.domain.tool.AiToolDefinition
import com.gromozeka.domain.tool.AiToolExecutionScope
import com.gromozeka.domain.tool.AiToolMetadata
import com.gromozeka.domain.tool.ToolExecutionContext
import kotlinx.coroutines.runBlocking
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GitHubCopilotRuntimeRealTest {
    @Test
    fun `real Copilot CLI returns final answer and completes a tool round trip when enabled`() = runBlocking {
        if (!realCopilotEnabled()) return@runBlocking

        val home = Files.createTempDirectory("gromozeka-copilot-real-")
        val connection = AiConnection.GitHubCopilot(
            id = AiConnection.Id("github-copilot-real"),
            displayName = "GitHub Copilot real test",
            enabled = true,
            executablePath = realCopilotExecutable(),
            executionTarget = AiExecutionTarget.Worker("copilot-real-test-worker"),
        )
        val model = AiModelConfiguration(
            id = AiModelConfiguration.Id("github-copilot-real-model"),
            connectionId = connection.id,
            providerModelId = realCopilotModel(),
            displayName = "GitHub Copilot real model",
            assistantResponseFormat = AiModelConfiguration.AssistantResponseFormat.TEXT,
        )
        val clientPool = GitHubCopilotClientPool(TestSettingsProvider(home))
        val runtime = GitHubCopilotRuntime(connection, model, clientPool, MissingCredentialRepository)

        try {
            val directResponse = runtime.call(
                request(listOf(userMessage("Return exactly COPILOT_DIRECT_OK.")))
            )
            assertTrue(directResponse.messages.single().text().contains("COPILOT_DIRECT_OK"))

            val tool = SquareTool()
            val firstUser = userMessage(
                "Call square_value with value 7. After receiving its result, return exactly " +
                    "COPILOT_TOOL_RESULT_<result>."
            )
            val toolResponse = runtime.call(
                request(
                    messages = listOf(firstUser),
                    tools = listOf(tool),
                    toolChoice = AiToolChoice.RequiredTool(tool.definition.name),
                )
            )
            val toolCall = toolResponse.toolCalls.single()
            assertEquals(tool.definition.name, toolCall.call.name)
            assertEquals(7, toolCall.call.input.jsonObject.getValue("value").jsonPrimitive.content.toInt())

            val toolOutput = tool.call(toolCall.call.input.toString())
            assertEquals("49", toolOutput)
            val finalResponse = runtime.call(
                request(
                    messages = listOf(
                        firstUser,
                        assistantMessage(toolResponse.messages.single().content),
                        toolResultMessage(toolCall, toolOutput),
                    ),
                    tools = listOf(tool),
                )
            )
            assertTrue(finalResponse.messages.single().text().contains("COPILOT_TOOL_RESULT_49"))
        } finally {
            clientPool.close()
            deleteRecursively(home)
        }
    }

    private fun request(
        messages: List<Conversation.Message>,
        tools: List<AiToolCallback> = emptyList(),
        toolChoice: AiToolChoice = AiToolChoice.Auto,
    ) = AiRuntimeRequest(
        systemPrompts = listOf("Follow the requested output contract exactly."),
        messages = messages,
        tools = tools,
        options = AiRuntimeOptions(
            toolChoice = toolChoice,
            assistantResponseFormat = AiModelConfiguration.AssistantResponseFormat.TEXT,
        ),
    )

    private fun userMessage(text: String) = Conversation.Message(
        id = Conversation.Message.Id("user-${Clock.System.now().toEpochMilliseconds()}"),
        conversationId = Conversation.Id("copilot-real-test"),
        role = Conversation.Message.Role.USER,
        content = listOf(Conversation.Message.ContentItem.UserMessage(text)),
        createdAt = Clock.System.now(),
    )

    private fun assistantMessage(content: List<Conversation.Message.ContentItem>) = Conversation.Message(
        id = Conversation.Message.Id("assistant-${Clock.System.now().toEpochMilliseconds()}"),
        conversationId = Conversation.Id("copilot-real-test"),
        role = Conversation.Message.Role.ASSISTANT,
        content = content,
        createdAt = Clock.System.now(),
    )

    private fun toolResultMessage(
        toolCall: Conversation.Message.ContentItem.ToolCall,
        output: String,
    ) = Conversation.Message(
        id = Conversation.Message.Id("tool-${Clock.System.now().toEpochMilliseconds()}"),
        conversationId = Conversation.Id("copilot-real-test"),
        role = Conversation.Message.Role.USER,
        content = listOf(
            Conversation.Message.ContentItem.ToolResult(
                toolUseId = toolCall.id,
                toolName = toolCall.call.name,
                result = listOf(Conversation.Message.ContentItem.ToolResult.Data.Text(output)),
            )
        ),
        createdAt = Clock.System.now(),
    )

    private fun com.gromozeka.domain.model.ai.AiAssistantMessage.text(): String =
        content.filterIsInstance<Conversation.Message.ContentItem.AssistantMessage>()
            .joinToString("\n") { it.structured.fullText }

    private class SquareTool : AiToolCallback {
        override val definition = AiToolDefinition(
            name = "square_value",
            description = "Returns the square of an integer.",
            inputSchema = """{"type":"object","properties":{"value":{"type":"integer"}},"required":["value"],"additionalProperties":false}""",
        )
        override val metadata = AiToolMetadata(executionScope = AiToolExecutionScope.SERVER)

        override fun call(toolInput: String, context: ToolExecutionContext?): String {
            val value = Json.parseToJsonElement(toolInput).jsonObject.getValue("value").jsonPrimitive.content.toInt()
            return (value * value).toString()
        }
    }

    private class TestSettingsProvider(home: Path) : SettingsProvider {
        override val userProfile = UserProfile()
        override val userDeviceSettings = UserDeviceSettings.Desktop()
        override val mode = AppMode.TEST
        override val homeDirectory = home.toAbsolutePath().toString()
    }

    private object MissingCredentialRepository : AiUserCredentialRepository {
        override suspend fun find(userId: com.gromozeka.domain.model.User.Id, connectionId: AiConnection.Id) = null

        override suspend fun save(
            userId: com.gromozeka.domain.model.User.Id,
            connectionId: AiConnection.Id,
            secret: String,
            updatedAt: Instant,
        ) = error("Not supported")

        override suspend fun delete(
            userId: com.gromozeka.domain.model.User.Id,
            connectionId: AiConnection.Id,
        ) = false
    }

    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { files ->
            files.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    private fun realCopilotEnabled(): Boolean =
        System.getProperty("gromozeka.copilot.real") == "true" ||
            System.getenv("GROMOZEKA_COPILOT_REAL") == "true"

    private fun realCopilotExecutable(): String =
        System.getProperty("gromozeka.copilot.executable")
            ?.takeIf(String::isNotBlank)
            ?: System.getenv("GROMOZEKA_COPILOT_EXECUTABLE")?.takeIf(String::isNotBlank)
            ?: "copilot"

    private fun realCopilotModel(): String =
        System.getProperty("gromozeka.copilot.model")
            ?.takeIf(String::isNotBlank)
            ?: System.getenv("GROMOZEKA_COPILOT_MODEL")?.takeIf(String::isNotBlank)
            ?: "gpt-5.6-terra"
}
