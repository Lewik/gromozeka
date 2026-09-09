package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.LocalPasswordCredential
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.UserIdentity
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.postgresql.ds.PGSimpleDataSource
import java.util.UUID
import kotlin.test.*
import kotlin.time.Clock

class PostgresUserIdentityTest {
    @Test
    fun `identity migration observation concurrency and current authorship`() = runBlocking {
        if (System.getenv("GROMOZEKA_POSTGRES_RUNTIME_TEST") != "true") return@runBlocking
        val schema = "identity_test_${UUID.randomUUID().toString().replace("-", "")}"
        val source = PGSimpleDataSource().apply {
            setURL(System.getenv("GROMOZEKA_POSTGRES_URL") ?: "jdbc:postgresql://localhost:5434/gromozeka")
            user = System.getenv("GROMOZEKA_POSTGRES_USER") ?: "gromozeka"
            password = System.getenv("GROMOZEKA_POSTGRES_PASSWORD") ?: "gromozeka"
            currentSchema = "$schema,public"
        }
        try {
            fun flyway() = Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration/postgres")
            flyway().target("55").load().migrate()
            source.connection.use { connection -> connection.createStatement().use {
                it.execute("INSERT INTO users(id,username,display_name,status,role,created_at,updated_at) VALUES ('owner','owner','Owner','ACTIVE','OWNER',now(),now())")
                it.execute("INSERT INTO local_password_credentials(user_id,password_hash,password_changed_at) VALUES ('owner','test-hash',now())")
            } }
            flyway().load().migrate()
            Database.connect(source)
            val repository = ExposedIdentityRepository()
            val owner = assertNotNull(repository.findUserByUsername("owner"))
            assertEquals(listOf(UserIdentity.LocalLogin("owner")), owner.identities)
            assertTrue(owner.canLogin && owner.canUseAi)
            assertEquals("test-hash", repository.findPasswordCredential(owner.id)?.passwordHash)
            val telegram = UserIdentity.Telegram(783121, "A friend", "owner")
            val observed = coroutineScope {
                (1..6).map { async { repository.observeTelegramIdentity(telegram, Clock.System.now()) } }.awaitAll()
            }
            val friend = observed.first()
            assertEquals(1, observed.map { it.id }.distinct().size)
            assertNotEquals(owner.id, friend.id)
            assertNull(friend.username)
            assertFalse(friend.canLogin || friend.canUseAi)
            assertNull(repository.findPasswordCredential(friend.id))
            val renamed = repository.observeTelegramIdentity(telegram.copy(displayName = "New name"), Clock.System.now())
            assertEquals(friend.id, renamed.id)
            assertEquals("New name", renamed.displayName)
            val message = Conversation.Message(
                id = Conversation.Message.Id("old-message"),
                conversationId = Conversation.Id("channel"), role = Conversation.Message.Role.USER,
                author = Conversation.Message.Author.User(friend.id, "Old name", telegram.key),
                content = emptyList(), createdAt = Clock.System.now(),
            )
            assertEquals("New name", dbQuery { resolveCurrentMessageAuthors(listOf(message)) }.single().author?.displayName)
            source.connection.use { connection -> connection.prepareStatement("UPDATE user_identities SET user_id = ? WHERE identity_key = ?").use {
                it.setString(1, owner.id.value)
                it.setString(2, telegram.key)
                it.executeUpdate()
            } }
            val linked = dbQuery { resolveCurrentMessageAuthors(listOf(message)) }.single().author as Conversation.Message.Author.User
            assertEquals(owner.id, linked.userId)
            assertEquals("Owner", linked.displayName)
            assertEquals(friend.id, (message.author as Conversation.Message.Author.User).userId)
            assertEquals(owner.id, repository.observeTelegramIdentity(telegram, Clock.System.now()).id)
            assertEquals("Owner", repository.findUserById(owner.id)?.displayName)
            repository.updateUser(owner.copy(loginAllowed = false, aiAllowed = false))
            assertEquals(0, repository.countActiveOwners())
            assertFalse(assertNotNull(repository.findUserById(owner.id)).canLogin)
        } finally {
            source.connection.use { it.createStatement().use { statement -> statement.execute("DROP SCHEMA IF EXISTS $schema CASCADE") } }
        }
    }
}
