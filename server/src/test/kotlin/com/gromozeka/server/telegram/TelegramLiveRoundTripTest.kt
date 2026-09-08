package com.gromozeka.server.telegram

import com.gromozeka.application.service.NamedSecretApplicationService
import com.gromozeka.domain.model.*
import com.gromozeka.domain.model.ai.*
import com.gromozeka.domain.repository.*
import com.gromozeka.domain.service.*
import com.gromozeka.infrastructure.ai.openai.subscription.OpenAiSubscriptionSession
import com.gromozeka.server.MemoryRealModelE2eNoToolsConfig
import com.gromozeka.server.testsupport.app.ServerTestHarness
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.*
import kotlin.time.Clock

@EnabledIfEnvironmentVariable(named = "GROMOZEKA_TELEGRAM_LIVE_ROUNDTRIP", matches = "true")
class TelegramLiveRoundTripTest {
    @Test fun `owner message crosses Telegram database actor model and delivery`() = runBlocking {
        val botUsername = environment("GROMOZEKA_TELEGRAM_TEST_BOT_USERNAME")
        val token = Files.readString(Path.of(environment("GROMOZEKA_TELEGRAM_TEST_TOKEN_FILE"))).trim()
        val chatId = environment("GROMOZEKA_TELEGRAM_TEST_CHAT_ID").toLong()
        val ownerId = environment("GROMOZEKA_TELEGRAM_TEST_OWNER_ID").toLong()
        val synthetic = System.getenv("GROMOZEKA_TELEGRAM_TEST_SYNTHETIC_INPUT") == "true"
        val updateId = if (synthetic) 1L else environment("GROMOZEKA_TELEGRAM_TEST_UPDATE_ID").toLong()
        val schema = environment("GROMOZEKA_TELEGRAM_TEST_SCHEMA")
        require(schema.matches(Regex("telegram_live_[a-z0-9_]+")))
        val session = readCodexSession(Path.of(environment("GROMOZEKA_OPENAI_SUBSCRIPTION_AUTH_FILE")))
        val api = TelegramHttpApi(token)
        val bot = api.call("getMe").jsonObject
        assertEquals(botUsername, bot.string("username"))
        assertTrue(api.call("getWebhookInfo").jsonObject.string("url").isNullOrEmpty())
        val incoming = if (synthetic) buildJsonObject {
            put("update_id", updateId)
            put("message", buildJsonObject {
                put("message_id", Int.MAX_VALUE); put("date", Clock.System.now().epochSeconds)
                put("chat", buildJsonObject { put("id", chatId); put("type", "group") })
                put("from", buildJsonObject { put("id", ownerId); put("first_name", "Synthetic integration test") })
                put("text", "@grz Это синтетический технический тест интеграции, не сообщение участника группы. Ответь ровно: Тест интеграции: запрос прошёл через Громозеку, ИИ и Telegram.")
            })
        } else api.call("getUpdates", buildJsonObject { put("timeout", 0); put("limit", 100) })
            .jsonArray.map { it.jsonObject }.single { it.long("update_id") == updateId }
        val message = incoming.getValue("message").jsonObject
        assertEquals(chatId, message.getValue("chat").jsonObject.long("id"))
        assertEquals(ownerId, message.getValue("from").jsonObject.long("id"))
        ServerTestHarness(subscriptionSession = session, systemProperties = mapOf(
            "gromozeka.postgres.jdbc-url" to environment("GROMOZEKA_POSTGRES_URL"),
            "gromozeka.postgres.schema" to schema,
            "gromozeka.llm.cassette.mode" to "off", "gromozeka.telegram.enabled" to "true",
            "gromozeka.ai.openai-subscription.websocket-response-timeout-ms" to "120000",
            "gromozeka.ai.openai-subscription.http-response-timeout-ms" to "120000",
        ), additionalSources = listOf(MemoryRealModelE2eNoToolsConfig::class.java), aiCatalogTransform = ::testCatalog).use { harness ->
            val context = harness.context
            context.getBean(TelegramBotLifecycle::class.java).stop()
            val authentication = context.getBean(AuthenticationService::class.java)
            check(!authentication.hasUsers()) { "Live test requires a fresh isolated schema" }
            val user = authentication.createFirstUser(
                requireNotNull(context.getBean(FirstUserBootstrapToken::class.java).currentToken()),
                "telegram-test", "Telegram test owner", java.util.UUID.randomUUID().toString().toCharArray(), "telegram-live-test",
            ).user
            val project = context.getBean(ProjectAccessService::class.java).create(user.id, "Telegram live test")
            val prompt = context.getBean(PromptDomainService::class.java).createPrompt(project.id, "Telegram test assistant",
                "You are a concise assistant in a Telegram group. Follow the authorized owner's current request. Do not use tools.")
            val agent = context.getBean(AgentDomainService::class.java).createAgent(project.id, "Telegram test", listOf(prompt.id), selection,
                toolAccess = com.gromozeka.domain.tool.ToolAccessPolicy.AllowOnly(),
                runtimeOverrides = AiRuntimeOverrides(maxOutputTokens = 1024, reasoning = AiReasoningConfig(effort = AiReasoningEffort.LOW)))
            val conversations = context.getBean(ConversationDomainService::class.java)
            val conversation = conversations.create(project.id,
                setOf(Conversation.Participant.User(user.id), Conversation.Participant.Agent(agent.id)), "Telegram live round trip")
            val binding = TelegramConversationBinding(chatId, topicId = message.long("message_thread_id"), conversationId = conversation.id,
                initiatorTelegramUserId = ownerId, routes = listOf(TelegramAgentRoute(agent.id)))
            val management = context.getBean(TelegramManagementService::class.java)
            context.getBean(NamedSecretApplicationService::class.java).save(user.id, "telegram_test", "Explicit live test bot", token)
            val configured = management.save(user, TelegramConnection(checkNotNull(bot.long("id")), botUsername, user.id, "telegram_test", bindings = listOf(binding)), 0)
            val policy = TelegramInboundPolicy(context.getBean(IdentityRepository::class.java), conversations)
            val prepared = assertNotNull(policy.prepare(configured.copy(enabled = true, acceptTriggersAfterEpochSeconds = 0), incoming))
            assertEquals(1, prepared.routes.size)
            val observed = context.getBean(IdentityRepository::class.java).findUserByIdentityKey("telegram:$ownerId")!!
            assertFalse(observed.loginAllowed); assertFalse(observed.aiAllowed); assertNotEquals(user.id, observed.id)
            val repository = context.getBean(TelegramChannelRepository::class.java)
            val enabled = management.save(user, configured.copy(enabled = true), configured.revision)
            val lease = assertNotNull(repository.openExclusiveSession(configured.id))
            try { lease.save(TelegramBotState(nextUpdateId = updateId + 1, inbox = listOf(prepared.copy(activationRevision = enabled.activationRevision)))) } finally { lease.close() }
            if (!synthetic) context.getBean(TelegramBotLifecycle::class.java).start()
            println("Telegram live test prepared: schema=$schema conversation=${conversation.id.value} model=$modelName")
            assertTrue(context.getBean(AiToolProvider::class.java).getTools().isEmpty())
            val manualLease = if (synthetic) assertNotNull(repository.openExclusiveSession(configured.id)) else null
            val manualProcessor = manualLease?.let { TelegramBotProcessor(enabled, api, context.getBean(TelegramConversationGateway::class.java), it, { null }) }
            manualProcessor?.initialize()
            try { withTimeout(180_000) {
                while (true) {
                    manualProcessor?.synchronize()
                    manualProcessor?.typing()
                    val state = repository.find(configured.id)
                    val invocation = state?.invocations?.firstOrNull { it.sourceMessageId == prepared.sourceMessageId }
                    if (invocation != null) {
                        assertFalse(invocation.failed, "Telegram invocation failed in the runtime")
                        val deliveries = state.deliveries.filter { it.invocationId == invocation.id }
                        check(deliveries.none { it.state in setOf(TelegramDelivery.State.FAILED, TelegramDelivery.State.UNKNOWN) }) {
                            "Telegram delivery failed or is uncertain; inspect the group before retrying"
                        }
                        if (invocation.completed && deliveries.isNotEmpty() && deliveries.all { it.state == TelegramDelivery.State.SENT }) {
                            assertEquals(1, deliveries.size)
                            assertEquals(invocation.statusMessageId, deliveries.single().telegramMessageId)
                            assertFalse(invocation.publishedStatusText.orEmpty().contains("Completed"))
                            println("Telegram round trip passed: invocation=${invocation.id} status=${invocation.statusMessageId} replies=${deliveries.map { it.telegramMessageId }}")
                            break
                        }
                    }
                    delay(500)
                }
            } } finally { manualLease?.close() }
        }
    }

    private fun testCatalog(catalog: AiCatalog): AiCatalog = catalog.copy(connections = catalog.connections.map {
        when (it) {
            is AiConnection.OpenAiSubscription -> it.copy(enabled = true, webSearchEnabled = true)
            is AiConnection.OpenAiApi -> it.copy(enabled = false)
            is AiConnection.OpenAiCompatible -> it.copy(enabled = false)
            is AiConnection.GitHubCopilot -> it.copy(enabled = false)
            is AiConnection.AnthropicApi -> it.copy(enabled = false)
            is AiConnection.AnthropicBedrock -> it.copy(enabled = false)
            is AiConnection.ClaudeCode -> it.copy(enabled = false)
            is AiConnection.GeminiApi -> it.copy(enabled = false)
            is AiConnection.Ollama -> it.copy(enabled = false)
        }
    }, modelConfigurations = catalog.modelConfigurations.map {
        if (it.id == selection.modelConfigurationId) it.copy(defaultParameters = it.defaultParameters.copy(
            reasoning = AiReasoningConfig(effort = AiReasoningEffort.LOW), maxOutputTokens = 1024)) else it
    })

    private fun readCodexSession(path: Path): OpenAiSubscriptionSession {
        val tokens = Json.parseToJsonElement(Files.readString(path)).jsonObject.getValue("tokens").jsonObject
        val accessToken = tokens.getValue("access_token").jsonPrimitive.content
        val claims = Json.parseToJsonElement(Base64.getUrlDecoder().decode(accessToken.split('.')[1]).decodeToString()).jsonObject
        val expiresAt = claims.getValue("exp").jsonPrimitive.long
        require(expiresAt > Clock.System.now().epochSeconds + 600) { "Codex access token needs renewal before the live test" }
        return OpenAiSubscriptionSession(accessToken, "unused", null, tokens.getValue("account_id").jsonPrimitive.content, expiresAt * 1000)
    }
    private fun environment(name: String): String = requireNotNull(System.getenv(name)) { "Set $name explicitly" }
    private val modelName = "gpt-5.6-luna"
    private val selection = AiRuntimeSelection(AiModelConfiguration.Id("openai-subscription-$modelName"))
}
