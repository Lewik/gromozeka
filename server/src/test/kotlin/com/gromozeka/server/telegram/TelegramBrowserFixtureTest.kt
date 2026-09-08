package com.gromozeka.server.telegram

import com.gromozeka.application.service.NamedSecretApplicationService
import com.gromozeka.domain.model.*
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import com.gromozeka.domain.repository.*
import com.gromozeka.domain.service.*
import com.gromozeka.server.*
import com.gromozeka.server.testsupport.app.ServerTestHarness
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import kotlinx.coroutines.*
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.time.Clock

@EnabledIfEnvironmentVariable(named = "GROMOZEKA_TELEGRAM_BROWSER_TEST", matches = "true")
class TelegramBrowserFixtureTest {
    @Test fun `serve isolated Telegram settings and mirrored history for visual checks`() = runBlocking {
        val schema = requireNotNull(System.getenv("GROMOZEKA_TELEGRAM_TEST_SCHEMA"))
        require(schema.matches(Regex("telegram_ui_[a-z0-9_]+")))
        val root = File(requireNotNull(System.getenv("GROMOZEKA_TELEGRAM_TEST_WEB_ROOT")))
        require(root.resolve("index.html").isFile)
        ServerTestHarness(subscriptionSession = null, systemProperties = mapOf(
            "gromozeka.postgres.jdbc-url" to requireNotNull(System.getenv("GROMOZEKA_POSTGRES_URL")),
            "gromozeka.postgres.schema" to schema, "gromozeka.telegram.enabled" to "true",
            "gromozeka.llm.cassette.mode" to "replay-only",
        ), additionalSources = listOf(MemoryRealModelE2eNoToolsConfig::class.java), aiCatalogTransform = { catalog ->
            catalog.copy(connections = catalog.connections.map {
                if (it is com.gromozeka.domain.model.ai.AiConnection.OpenAiSubscription) it.copy(enabled = true) else it
            })
        }).use { harness ->
            val context = harness.context
            context.getBean(TelegramBotLifecycle::class.java).stop()
            val auth = context.getBean(AuthenticationService::class.java)
            check(!auth.hasUsers()) { "Use a fresh isolated browser fixture schema" }
            val bootstrap = context.getBean(FirstUserBootstrapToken::class.java)
            val user = auth.createFirstUser(bootstrap.currentToken()!!, "telegram-ui", "Telegram UI owner",
                "telegram-ui-test-password".toCharArray(), "visual-test").user
            val identities = context.getBean(IdentityRepository::class.java)
            val friend = identities.observeTelegramIdentity(UserIdentity.Telegram(99112233, "Telegram friend"), Clock.System.now())
            val project = context.getBean(ProjectAccessService::class.java).create(user.id, "Telegram visual checks")
            val agentService = context.getBean(AgentDomainService::class.java)
            val selection = AiRuntimeSelection(AiModelConfiguration.Id("openai-subscription-gpt-5.6-luna"))
            val prompt = context.getBean(PromptDomainService::class.java).createPrompt(project.id, "Visual fixture", "This is a local UI fixture. No model calls are expected.")
            val first = agentService.createAgent(project.id, "Gromozeka", listOf(prompt.id), selection)
            val second = agentService.createAgent(project.id, "Reviewer", listOf(prompt.id), selection)
            val conversations = context.getBean(ConversationDomainService::class.java)
            val conversation = conversations.create(project.id,
                setOf(Conversation.Participant.User(user.id), Conversation.Participant.Agent(first.id), Conversation.Participant.Agent(second.id)), "Telegram group — read only")
            val local = conversations.create(project.id, setOf(Conversation.Participant.User(user.id)), "Normal conversation")
            val binding = TelegramConversationBinding(-865117694, conversationId = conversation.id, initiatorTelegramUserId = 68575695,
                routes = listOf(TelegramAgentRoute(first.id), TelegramAgentRoute(second.id, "review", 30)))
            val config = context.getBean(TelegramConnectionRepository::class.java).save(TelegramConnection(8316688933,
                "gromozeka_lev_test_bot", user.id, "telegram_visual_test", bindings = listOf(binding)), 0)
            System.getenv("GROMOZEKA_TELEGRAM_TEST_TOKEN_FILE")?.let { tokenFile ->
                context.getBean(NamedSecretApplicationService::class.java).save(user.id, config.tokenSecretName, "Explicit visual test bot", Files.readString(Path.of(tokenFile)).trim())
            }
            val ingress = context.getBean(ExternalConversationIngressService::class.java)
            for ((index, author) in listOf(user, friend).withIndex()) {
                ingress.importMessage(config.channel(binding), Conversation.Message(Conversation.Message.Id("visual-$index"), conversation.id,
                    role = Conversation.Message.Role.USER, author = Conversation.Message.Author.User(author.id, author.displayName,
                        author.identities.filterIsInstance<UserIdentity.Telegram>().firstOrNull()?.key),
                    content = listOf(Conversation.Message.ContentItem.UserMessage(if (index == 0) "Local preview fixture. No Telegram messages are sent." else "A friend's message is stored as history without starting AI.\n\n**Markdown** and author names remain visible.")),
                    createdAt = Clock.System.now()))
            }
            context.getBean(UserConversationTabLayoutService::class.java).open(user.id, conversation.id)
            val remote = context.getBean(GromozekaRemoteServer::class.java)
            val http = embeddedServer(CIO, host = "127.0.0.1", port = 8767) {
                installHttpAuthenticationErrors()
                install(gromozekaBrowserSecurityHeaders)
                install(WebSockets) { maxFrameSize = Long.MAX_VALUE }
                routing {
                    gromozekaAuthentication(auth, bootstrap, context.getBean(AuthenticationAttemptLimiter::class.java), secureCookie = false)
                    webSocket("/ws") { remote.handle(this, call.requireAuthenticated(auth)) }
                    gromozekaWeb(root)
                }
            }.start(wait = false)
            println("Telegram visual fixture ready at http://127.0.0.1:8767/ ; conversation=${conversation.id.value}; local=${local.id.value}; user=telegram-ui")
            try { delay((System.getenv("GROMOZEKA_TELEGRAM_TEST_HOLD_SECONDS")?.toLong() ?: 900).coerceIn(1, 1800) * 1000) }
            finally { http.stop(500, 2000) }
        }
    }
}
