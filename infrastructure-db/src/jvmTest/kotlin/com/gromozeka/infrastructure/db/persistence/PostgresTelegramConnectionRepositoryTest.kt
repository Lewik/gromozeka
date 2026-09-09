package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.*
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.postgresql.ds.PGSimpleDataSource
import java.util.UUID
import kotlin.test.*
import kotlin.time.Clock

class PostgresTelegramConnectionRepositoryTest {
    @Test fun `configuration and conversation binding commit together with exclusive ownership and revision checks`() = runBlocking {
        if (System.getenv("GROMOZEKA_POSTGRES_RUNTIME_TEST") != "true") return@runBlocking
        val schema = "telegram_config_${UUID.randomUUID().toString().replace("-", "")}"
        val source = PGSimpleDataSource().apply {
            setURL(System.getenv("GROMOZEKA_POSTGRES_URL")); user = "gromozeka"; password = "gromozeka"; currentSchema = "$schema,public"
        }
        try {
            Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).locations("classpath:db/migration/postgres").load().migrate()
            Database.connect(source)
            val now = Clock.System.now()
            val owner = ExposedIdentityRepository().observeTelegramIdentity(UserIdentity.Telegram(123, "Owner"), now)
            val project = ExposedProjectRepository().save(Project(Project.Id("p"), "Group project", createdAt = now, lastUsedAt = now))
            val conversations = ExposedConversationRepository()
            val conversation = conversations.create(Conversation(Conversation.Id("c"), project.id,
                setOf(Conversation.Participant.User(owner.id)), currentThread = Conversation.Thread.Id("t"), createdAt = now, updatedAt = now))
            val route = TelegramAgentRoute(AgentDefinition.Id("agent"))
            val binding = TelegramConversationBinding(-456, conversationId = conversation.id, initiatorTelegramUserId = 123, routes = listOf(route))
            val repository = PostgresTelegramConnectionRepository(source)
            val connection = TelegramConnection(12345, "test_bot", owner.id, "telegram", bindings = listOf(binding))
            val saved = repository.save(connection, 0)
            assertEquals(1, saved.revision)
            assertEquals(saved, repository.find(saved.id))
            assertEquals(ExternalConversationChannel("telegram", saved.id, binding.key), conversations.findById(conversation.id)?.externalChannel)
            assertFails { repository.save(connection, 0) }
            assertFails { repository.save(connection.copy(botId = 67890, botUsername = "other_bot"), 0) }
            assertNull(repository.find("67890"))
            assertEquals(saved, repository.find(saved.id))
            assertFails { conversations.delete(conversation.id) }
            val disabled = repository.save(saved.copy(enabled = false), saved.revision)
            assertNotNull(conversations.findById(conversation.id)?.externalChannel)
            repository.save(disabled.copy(bindings = emptyList()), disabled.revision)
            assertNull(conversations.findById(conversation.id)?.externalChannel)
            conversations.delete(conversation.id)
            assertNull(conversations.findById(conversation.id))
        } finally {
            source.connection.use { it.createStatement().use { statement -> statement.execute("DROP SCHEMA IF EXISTS $schema CASCADE") } }
        }
    }
}
