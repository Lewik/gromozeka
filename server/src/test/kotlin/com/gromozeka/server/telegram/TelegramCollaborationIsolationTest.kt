package com.gromozeka.server.telegram

import com.gromozeka.application.service.AgentCollaborationService
import com.gromozeka.application.service.DistributedAiToolCatalog
import com.gromozeka.domain.model.*
import com.gromozeka.domain.model.ai.*
import com.gromozeka.domain.repository.*
import com.gromozeka.domain.service.*
import com.gromozeka.domain.tool.*
import com.gromozeka.server.testsupport.app.ServerTestHarness
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.config.BeanFactoryPostProcessor
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*
import kotlin.time.Clock

@EnabledIfEnvironmentVariable(named = "GROMOZEKA_POSTGRES_RUNTIME_TEST", matches = "true")
class TelegramCollaborationIsolationTest {
    @Test
    fun `Telegram answers normally with collaboration enabled while ordinary conversations retain collaboration`(): Unit = runBlocking {
        val schema = "telegram_collab_${UUID.randomUUID().toString().replace("-", "")}"
        val url = requireNotNull(System.getenv("GROMOZEKA_POSTGRES_URL"))
        val username = System.getenv("GROMOZEKA_POSTGRES_USER") ?: "gromozeka"
        val password = System.getenv("GROMOZEKA_POSTGRES_PASSWORD") ?: "gromozeka"
        val defaults = ServerTestHarness.defaultSettings()
        val settings = defaults.copy(userProfile = defaults.userProfile.copy(
            memorySettings = defaults.userProfile.memorySettings.copy(autoRemember = false, autoRecall = false),
            suggestedRepliesSettings = defaults.userProfile.suggestedRepliesSettings.copy(mode = UserProfile.SuggestedRepliesSettings.Mode.DISABLED),
        ))
        try {
            ServerTestHarness(settings = settings, subscriptionSession = null, systemProperties = mapOf(
                "gromozeka.postgres.jdbc-url" to url,
                "gromozeka.postgres.username" to username,
                "gromozeka.postgres.password" to password,
                "gromozeka.postgres.schema" to schema,
                "gromozeka.collaboration.enabled" to "true",
                "gromozeka.telegram.enabled" to "true",
                "gromozeka.llm.cassette.mode" to "replay-only",
            ), additionalSources = listOf(TelegramIsolationModelConfig::class.java)).use { harness ->
                val context = harness.context
                // No Telegram HTTP calls: stop polling before creating the synthetic connection.
                context.getBean(TelegramBotLifecycle::class.java).stop()
                assertTrue(context.getBean(TelegramConnectionApiFactory::class.java).serverEnabled)
                assertNotNull(context.getBean(AgentCollaborationService::class.java))
                assertTrue(context.getBean(AiToolProvider::class.java).getTools().map { it.definition.name }.containsAll(AGENT_COLLABORATION_TOOL_NAMES))
                val model = context.getBean(TelegramIsolationModel::class.java)
                val owner = context.getBean(AuthenticationService::class.java).createFirstUser(
                    requireNotNull(context.getBean(FirstUserBootstrapToken::class.java).currentToken()),
                    "isolation", "Isolation test owner", "synthetic-test-password".toCharArray(), "test",
                ).user
                val project = context.getBean(ProjectAccessService::class.java).create(owner.id, "Channel isolation")
                val prompt = context.getBean(PromptDomainService::class.java).createPrompt(project.id, "Test", "Answer briefly.")
                val agent = context.getBean(AgentDomainService::class.java).createAgent(
                    project.id, "Test agent", listOf(prompt.id), ServerTestHarness.openAiSubscriptionRuntimeSelection(),
                    runtimeOverrides = AiRuntimeOverrides(maxOutputTokens = 256),
                    toolAccess = ToolAccessPolicy.AllowOnly((AGENT_COLLABORATION_TOOL_NAMES + "search_tools")
                        .map { ToolSelector.ByName(QualifiedToolName("gromozeka", it)) }.toSet()),
                )
                val conversations = context.getBean(ConversationDomainService::class.java)
                val participants = setOf(Conversation.Participant.User(owner.id), Conversation.Participant.Agent(agent.id))
                val telegram = conversations.create(project.id, participants, "Telegram")
                val route = TelegramAgentRoute(agent.id)
                val binding = TelegramConversationBinding(-123, conversationId = telegram.id, initiatorTelegramUserId = 42, routes = listOf(route))
                val connection = context.getBean(TelegramConnectionRepository::class.java).save(
                    TelegramConnection(12345, "synthetic_bot", owner.id, "unused-token", enabled = true, bindings = listOf(binding)), 0,
                )
                val root = Conversation.Message(Conversation.Message.Id("telegram-isolation-root"), telegram.id,
                    role = Conversation.Message.Role.USER, author = Conversation.Message.Author.User(owner.id, owner.displayName),
                    content = listOf(Conversation.Message.ContentItem.UserMessage("Synthetic test: reply CHANNEL_OK")), createdAt = Clock.System.now())
                val ingress = context.getBean(ExternalConversationIngressService::class.java)
                assertTrue(ingress.importMessage(connection.channel(binding), root))
                withTimeout(15_000) {
                    while (conversations.loadCurrentMessages(telegram.id).none { it.id == root.id }) delay(20)
                }
                val invocation = TelegramInvocation("telegram-isolation-turn", connection.id, owner.id, binding, route, root.id, 1,
                    activationRevision = connection.activationRevision)
                val lease = assertNotNull(context.getBean(TelegramChannelRepository::class.java).openExclusiveSession(connection.id))
                try { lease.save(TelegramBotState(invocations = listOf(invocation))) } finally { lease.close() }
                context.getBean(TelegramConversationGateway::class.java).submit(invocation)
                val coordinator = context.getBean(ConversationRuntimeCoordinator::class.java)
                suspend fun awaitAnswer(id: Conversation.Id) = withTimeout(15_000) {
                    while (true) {
                        val state = coordinator.snapshot(id)
                        assertTrue(state.incidents.isEmpty(), state.incidents.toString())
                        val answer = conversations.loadCurrentMessages(id).flatMap { it.content }
                            .filterIsInstance<Conversation.Message.ContentItem.AssistantMessage>().any { it.structured.fullText == "CHANNEL_OK" }
                        if (answer && state.activeTask == null && state.pendingTasks.isEmpty()) break
                        delay(20)
                    }
                }
                awaitAnswer(telegram.id)
                val request = model.requests.single()
                assertEquals("CONVERSATION", request.options.usagePurpose)
                assertTrue(request.systemPrompts.none { it.contains("Cross-thread collaboration (experimental") })
                assertTrue(request.tools.none { it.definition.name in AGENT_COLLABORATION_TOOL_NAMES })
                val bound = assertNotNull(conversations.findById(telegram.id))
                val catalog = context.getBean(DistributedAiToolCatalog::class.java).snapshot(project, agent.toolAccess, bound)
                assertTrue(catalog.entries.values.none { it.logicalName in AGENT_COLLABORATION_TOOL_NAMES })
                assertTrue(AGENT_COLLABORATION_TOOL_NAMES.none { it in catalog.environmentPrompt })
                DriverManager.getConnection(url, username, password).use { c -> c.createStatement().use { s ->
                    s.executeQuery("SELECT count(*) FROM $schema.agent_response_drafts").use { rows -> rows.next(); assertEquals(0, rows.getInt(1)) }
                } }
                val ordinary = conversations.create(project.id, participants, "Ordinary conversation")
                assertTrue(context.getBean(ConversationRuntimeIngressService::class.java).invokeAgent(owner, ordinary.id,
                    root.copy(id = Conversation.Message.Id("ordinary-isolation-root"), conversationId = ordinary.id), agent.id))
                awaitAnswer(ordinary.id)
                assertEquals(2, model.requests.size)
                val normal = model.requests.last()
                assertTrue(normal.systemPrompts.any { it.contains("Cross-thread collaboration (experimental") })
                val normalCatalog = context.getBean(DistributedAiToolCatalog::class.java).snapshot(project, agent.toolAccess, ordinary)
                val collaborationModelNames = normalCatalog.entries.values.filter { it.logicalName in AGENT_COLLABORATION_TOOL_NAMES }.map { it.modelName }
                assertEquals(AGENT_COLLABORATION_TOOL_NAMES.size, collaborationModelNames.size)
                assertTrue(normal.tools.map { it.definition.name }.containsAll(collaborationModelNames), normal.tools.map { it.definition.name }.toString())
            }
        } finally {
            DriverManager.getConnection(url, username, password).use { c -> c.createStatement().use { it.execute("DROP SCHEMA IF EXISTS $schema CASCADE") } }
        }
    }
}

@TestConfiguration(proxyBeanMethods = false)
class TelegramIsolationModelConfig {
    companion object {
        @Bean @JvmStatic
        fun useIsolationModel(): BeanFactoryPostProcessor = BeanFactoryPostProcessor { factory ->
            factory.getBeanDefinition("aiRuntimeProvider").isPrimary = false
            // Other E2E fixtures suppress tools; this test must exercise the real contributor/catalog path.
            factory.getBeanDefinition("aiToolProvider").isPrimary = false
            factory.getBeanDefinition("defaultAiToolProvider").isPrimary = true
        }
    }
    @Bean @Primary
    fun telegramIsolationModel() = TelegramIsolationModel()
}

class TelegramIsolationModel : AiRuntimeProvider {
    val requests = CopyOnWriteArrayList<AiRuntimeRequest>()
    override fun getRuntime(selection: AiRuntimeSelection, workspaceRootPath: String?): AiRuntime = object : AiRuntime {
        override suspend fun call(request: AiRuntimeRequest): AiRuntimeResponse {
            // Catalog summaries are unrelated background work; serve them deterministically without external AI.
            if (request.options.usagePurpose == "TOOL_CAPABILITY_CATALOG") {
                var names: JsonElement = (request.options.responseFormat as AiResponseFormat.JsonSchema).schema
                for (key in listOf("properties", "categories", "items", "properties", "tool_names", "items", "enum")) {
                    names = names.jsonObject.getValue(key)
                }
                return response("""{"overview":"Synthetic test tools","categories":[{"id":"test_tools","label":"Test tools","summary":"Tools for the isolation test","tool_names":$names}]}""")
            }
            requests += request
            check(request.options.usagePurpose == "CONVERSATION") { "Unexpected secondary AI call: ${request.options.usagePurpose}" }
            return response("CHANNEL_OK")
        }
        override fun stream(request: AiRuntimeRequest): Flow<AiRuntimeResponse> = flow { emit(call(request)) }
    }
    private fun response(text: String) = AiRuntimeResponse(listOf(AiAssistantMessage(listOf(
        Conversation.Message.ContentItem.AssistantMessage(Conversation.Message.StructuredText(text)),
    ))))
}
