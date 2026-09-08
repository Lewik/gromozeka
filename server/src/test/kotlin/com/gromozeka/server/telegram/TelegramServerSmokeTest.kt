package com.gromozeka.server.telegram

import com.gromozeka.domain.repository.TelegramChannelRepository
import com.gromozeka.domain.repository.TelegramConnectionRepository
import com.gromozeka.domain.repository.IdentityRepository
import com.gromozeka.domain.model.*
import com.gromozeka.domain.service.*
import com.gromozeka.domain.service.ConversationRequestEnricher
import com.gromozeka.server.testsupport.app.ServerTestHarness
import kotlinx.coroutines.*
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Clock

@EnabledIfEnvironmentVariable(named = "GROMOZEKA_POSTGRES_RUNTIME_TEST", matches = "true")
class TelegramServerSmokeTest {
    @Test
    fun `server migrates and wires Telegram without enabling it`(): Unit = runBlocking {
        val schema = "telegram_smoke_${UUID.randomUUID().toString().replace("-", "")}"
        val jdbcUrl = requireNotNull(System.getenv("GROMOZEKA_POSTGRES_URL"))
        val username = System.getenv("GROMOZEKA_POSTGRES_USER") ?: "gromozeka"
        val password = System.getenv("GROMOZEKA_POSTGRES_PASSWORD") ?: "gromozeka"
        try {
            ServerTestHarness(
                subscriptionSession = null,
                systemProperties = mapOf(
                    "gromozeka.postgres.jdbc-url" to jdbcUrl,
                    "gromozeka.postgres.username" to username,
                    "gromozeka.postgres.password" to password,
                    "gromozeka.postgres.schema" to schema,
                    "gromozeka.telegram.enabled" to "false",
                    "gromozeka.llm.cassette.mode" to "replay-only",
                ),
                aiCatalogTransform = { it },
            ).use { harness ->
                val context = harness.context
                assertFalse(context.getBean(TelegramConnectionApiFactory::class.java).serverEnabled)
                assertFalse(context.getBean(TelegramBotLifecycle::class.java).isRunning)
                assertTrue(context.getBeansOfType(ConversationRequestEnricher::class.java)
                    .values.any { it is TelegramRequestEnricher })
                assertTrue(context.getBean(TelegramConversationGateway::class.java) is TelegramRuntimeGateway)
                assertNull(context.getBean(TelegramChannelRepository::class.java).find("disabled_smoke_bot"))
                val authentication = context.getBean(AuthenticationService::class.java)
                val owner = authentication.createFirstUser(context.getBean(FirstUserBootstrapToken::class.java).currentToken()!!,
                    "owner", "Owner", "smoke-test-password".toCharArray(), "telegram-test").user
                val friend = context.getBean(IdentityRepository::class.java)
                    .observeTelegramIdentity(UserIdentity.Telegram(42, "Friend"), Clock.System.now())
                val project = context.getBean(ProjectAccessService::class.java).create(owner.id, "Mirroring")
                val conversations = context.getBean(ConversationDomainService::class.java)
                val conversation = conversations.create(project.id, setOf(Conversation.Participant.User(owner.id)), "Mirror")
                val binding = TelegramConversationBinding(-123, conversationId = conversation.id, initiatorTelegramUserId = 1,
                    routes = listOf(TelegramAgentRoute(AgentDefinition.Id("unused-passive-agent"))))
                val connection = context.getBean(TelegramConnectionRepository::class.java).save(
                    TelegramConnection(12345, "test_bot", owner.id, "unused-token", bindings = listOf(binding)), 0)
                val ingress = context.getBean(ExternalConversationIngressService::class.java)
                val original = Conversation.Message(Conversation.Message.Id("original"), conversation.id,
                    role = Conversation.Message.Role.USER, author = Conversation.Message.Author.User(friend.id, friend.displayName, "telegram:42"),
                    content = listOf(Conversation.Message.ContentItem.UserMessage("@grz this friend cannot trigger AI")), createdAt = Clock.System.now())
                assertTrue(ingress.importMessage(connection.channel(binding), original))
                suspend fun awaitMessage(id: Conversation.Message.Id) = withTimeout(15000) {
                    while (conversations.loadCurrentMessages(conversation.id).none { it.id == id }) delay(20)
                }
                awaitMessage(original.id)
                assertTrue(ingress.importMessage(connection.channel(binding), original))
                val edited = original.copy(id = Conversation.Message.Id("edited"), content = listOf(Conversation.Message.ContentItem.UserMessage("Edited by its real author")))
                assertTrue(ingress.importMessage(connection.channel(binding), edited, original.id))
                awaitMessage(edited.id)
                val history = conversations.loadCurrentMessages(conversation.id)
                assertEquals(1, history.size)
                assertEquals(listOf(original.id), history.single().originalIds)
                assertEquals(friend.id, (history.single().author as Conversation.Message.Author.User).userId)
                val runtime = context.getBean(ConversationRuntimeIngressService::class.java)
                assertFailsWith<IllegalArgumentException> { runtime.postMessage(owner, conversation.id, original.copy(id = Conversation.Message.Id("manual"))) }
                withTimeout(15000) {
                    while (context.getBean(ConversationRuntimeCoordinator::class.java).snapshot(conversation.id).let {
                        it.activeTask != null || it.pendingTasks.isNotEmpty()
                    }) delay(20)
                }
            }
        } finally {
            DriverManager.getConnection(jdbcUrl, username, password).use { connection ->
                connection.createStatement().use { it.execute("DROP SCHEMA IF EXISTS $schema CASCADE") }
            }
        }
    }
}
