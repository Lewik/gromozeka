package com.gromozeka.infrastructure.ai.openai.siwc

import com.gromozeka.domain.model.AiProvider
import com.gromozeka.domain.model.AppMode
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.UserDeviceSettings
import com.gromozeka.domain.model.UserProfile
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiModelCapability
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiModelSpec
import com.gromozeka.domain.model.ai.AiRuntimeOptions
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiRuntimeResponse
import com.gromozeka.domain.model.ai.AiStepOutcome
import com.gromozeka.domain.model.ai.AiToolChoice
import com.gromozeka.domain.service.ResolvedAiRuntime
import com.gromozeka.domain.service.SettingsProvider
import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.domain.tool.AiToolDefinition
import com.gromozeka.domain.tool.ServerToolMetadata
import com.gromozeka.domain.tool.ToolExecutionContext
import com.gromozeka.infrastructure.ai.openai.OpenAiResponsesContinuations
import com.gromozeka.infrastructure.ai.openai.OpenAiSdkClientFactory
import com.gromozeka.infrastructure.ai.runtime.RoutingAiRuntimeProvider
import com.gromozeka.infrastructure.ai.runtime.SdkHttpClientPool
import com.gromozeka.infrastructure.ai.runtime.TestAiAdmission
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

class OpenAiSiwcRealTest {
    @Test
    fun `official SIWC lists models and streams a Responses call`() = runBlocking {
        if (System.getenv("GROMOZEKA_OPENAI_SIWC_REAL") != "true") return@runBlocking
        val home = Path.of(requireNotNull(System.getenv("GROMOZEKA_OPENAI_SIWC_REAL_HOME")) {
            "Set GROMOZEKA_OPENAI_SIWC_REAL_HOME to a private absolute directory"
        }).toAbsolutePath()
        Files.createDirectories(home)
        val settings = TestSettings(home)
        val store = OpenAiSiwcCredentialStore(settings)
        val auth = OpenAiSiwcAuthService(store)
        val browser = OpenAiSiwcBrowserAuthService(auth)
        val authorizationFile = home.resolve("authorization-url.txt")
        try {
            if (auth.session()?.let { it.status == OpenAiSiwcStoredState.Status.CONNECTED && it.credentials != null } != true) {
                val authorization = browser.beginLogin().getOrThrow()
                Files.writeString(authorizationFile, authorization.url)
                println("Complete ChatGPT sign-in using the URL in $authorizationFile")
                withTimeout(10.minutes) {
                    while (auth.session()?.status != OpenAiSiwcStoredState.Status.CONNECTED) delay(250)
                }
            }
        } finally {
            browser.shutdown()
            Files.deleteIfExists(authorizationFile)
        }

        val models = OpenAiSiwcModelsClient(auth).list(Clock.System.now() + 2.minutes).models.map { it.id }
        assertTrue(models.isNotEmpty(), "The SIWC model catalog is empty")
        val requested = System.getenv("GROMOZEKA_OPENAI_SIWC_MODEL")?.takeIf(String::isNotBlank)
        val model = requested ?: listOf("gpt-5.6-sol", "gpt-5.6").firstOrNull(models::contains) ?: models.first()
        assertTrue(model in models, "Requested SIWC model is not visible: $model")

        val connection = AiConnection.OpenAiSiwc(AiConnection.Id("openai-siwc-real"), "SIWC real", enabled = true)
        val configuration = AiModelConfiguration(
            id = AiModelConfiguration.Id("openai-siwc-real-$model"),
            connectionId = connection.id,
            providerModelId = model,
            displayName = "SIWC $model",
            assistantResponseFormat = AiModelConfiguration.AssistantResponseFormat.TEXT,
        )
        val spec = AiModelSpec(
            id = model,
            provider = AiProvider.OPENAI,
            capabilities = setOf(AiModelCapability.TEXT_GENERATION),
            limits = AiModelSpec.Limits(AiModelSpec.Limits.TextGeneration(128_000)),
        )
        val clients = SdkHttpClientPool()
        try {
            val backend = OpenAiSiwcRuntimeBackend(
                clientFactory = OpenAiSdkClientFactory(settings, clients),
                accessTokens = auth,
                admission = TestAiAdmission(),
                continuations = OpenAiResponsesContinuations(),
            )
            val runtime = RoutingAiRuntimeProvider(listOf(backend))
                .getRuntime(ResolvedAiRuntime(connection, configuration, spec), null)
            val marker = "SIWC_OK_${UUID.randomUUID().toString().replace("-", "").take(12)}"
            val message = Conversation.Message(
                id = Conversation.Message.Id("siwc-real-user"),
                conversationId = Conversation.Id("siwc-real"),
                role = Conversation.Message.Role.USER,
                content = listOf(Conversation.Message.ContentItem.UserMessage("Reply with exactly $marker")),
                createdAt = Clock.System.now(),
            )
            val response = runtime.call(AiRuntimeRequest(
                systemPrompts = listOf("Follow the user's exact output instruction."),
                messages = listOf(message),
                options = AiRuntimeOptions(
                    assistantResponseFormat = AiModelConfiguration.AssistantResponseFormat.TEXT,
                    usagePurpose = "SIWC_REAL_TEST",
                ),
            ))
            assertEquals(AiStepOutcome.COMPLETE, response.outcome)
            val text = response.messages.flatMap { it.content }
                .filterIsInstance<Conversation.Message.ContentItem.AssistantMessage>()
                .joinToString("\n") { it.structured.fullText }
                .trim()
            assertEquals(marker, text)

            val tool = echoTool()
            val toolMarker = "SIWC_TOOL_${UUID.randomUUID().toString().replace("-", "").take(12)}"
            val toolPrompt = message.copy(
                id = Conversation.Message.Id("siwc-real-tool-user"),
                content = listOf(Conversation.Message.ContentItem.UserMessage(
                    "Call echo_marker once. After its result arrives, reply with exactly the returned text.",
                )),
            )
            val toolCallResponse = runtime.call(AiRuntimeRequest(
                systemPrompts = listOf("Use the required tool. After a tool result, return its text exactly and call no more tools."),
                messages = listOf(toolPrompt),
                tools = listOf(tool),
                options = AiRuntimeOptions(
                    toolChoice = AiToolChoice.RequiredTool("echo_marker"),
                    assistantResponseFormat = AiModelConfiguration.AssistantResponseFormat.TEXT,
                    usagePurpose = "SIWC_REAL_TOOL_TEST",
                ),
            ))
            assertEquals(AiStepOutcome.TOOL_CALLS, toolCallResponse.outcome)
            val toolCall = toolCallResponse.toolCalls.single()
            assertEquals("echo_marker", toolCall.call.name)
            val toolResult = Conversation.Message(
                id = Conversation.Message.Id("siwc-real-tool-result"),
                conversationId = toolPrompt.conversationId,
                role = Conversation.Message.Role.USER,
                content = listOf(
                    Conversation.Message.ContentItem.ToolResult(
                        toolUseId = toolCall.id,
                        toolName = toolCall.call.name,
                        result = listOf(Conversation.Message.ContentItem.ToolResult.Data.Text(toolMarker)),
                        isError = false,
                    )
                ),
                createdAt = Clock.System.now(),
            )
            val toolFinal = runtime.call(AiRuntimeRequest(
                systemPrompts = listOf("After a tool result, return its text exactly and call no more tools."),
                messages = listOf(toolPrompt) + toolCallResponse.persistedMessages(toolPrompt.conversationId) + toolResult,
                tools = listOf(tool),
                options = AiRuntimeOptions(
                    toolChoice = AiToolChoice.None,
                    assistantResponseFormat = AiModelConfiguration.AssistantResponseFormat.TEXT,
                    usagePurpose = "SIWC_REAL_TOOL_RESULT_TEST",
                ),
            ))
            assertEquals(AiStepOutcome.COMPLETE, toolFinal.outcome)
            assertEquals(toolMarker, toolFinal.assistantText())
            println("SIWC_REAL_PASS model=$model visible_models=${models.size}")
        } finally {
            clients.close()
        }
    }

    private fun echoTool(): AiToolCallback = object : AiToolCallback {
        override val metadata = ServerToolMetadata
        override val definition = AiToolDefinition(
            name = "echo_marker",
            description = "Returns a marker supplied by the harness.",
            inputSchema = """{"type":"object","properties":{"value":{"type":"string"}},"required":["value"]}""",
        )

        override fun call(toolInput: String, context: ToolExecutionContext?): String = toolInput
    }

    private fun AiRuntimeResponse.persistedMessages(conversationId: Conversation.Id): List<Conversation.Message> =
        messages.mapIndexed { index, message ->
            Conversation.Message(
                id = Conversation.Message.Id("siwc-real-assistant-$index"),
                conversationId = conversationId,
                role = Conversation.Message.Role.ASSISTANT,
                content = message.content,
                providerMetadata = JsonObject((providerMetadata + message.metadata).mapValues { (_, value) ->
                    when (value) {
                        is JsonElement -> value
                        is String -> JsonPrimitive(value)
                        is Boolean -> JsonPrimitive(value)
                        is Number -> JsonPrimitive(value)
                        else -> error("Unsupported SIWC test metadata value: ${value?.let { it::class.simpleName }}")
                    }
                }),
                createdAt = Clock.System.now(),
            )
        }

    private fun AiRuntimeResponse.assistantText(): String = messages.flatMap { it.content }
        .filterIsInstance<Conversation.Message.ContentItem.AssistantMessage>()
        .joinToString("\n") { it.structured.fullText }
        .trim()

    private class TestSettings(home: Path) : SettingsProvider {
        override val homeDirectory = home.toString()
        override val userProfile = UserProfile()
        override val userDeviceSettings = UserDeviceSettings.Desktop()
        override val mode = AppMode.TEST
    }
}
