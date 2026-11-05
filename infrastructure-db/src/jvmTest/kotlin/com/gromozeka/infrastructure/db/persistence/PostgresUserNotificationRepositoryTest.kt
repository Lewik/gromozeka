package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.*
import kotlinx.coroutines.*
import org.junit.Assume.assumeTrue
import org.postgresql.ds.PGSimpleDataSource
import java.util.UUID
import kotlin.test.*
import kotlin.time.Instant

class PostgresUserNotificationRepositoryTest {
    @Test fun inboxOwnershipPaginationDeduplicationAndDismissalSurviveRepositoryRestart() = runBlocking {
        assumeTrue("Requires isolated PostgreSQL test configuration", System.getenv("GROMOZEKA_POSTGRES_RUNTIME_TEST") == "true")
        val schema = "notification_test_${UUID.randomUUID().toString().replace("-", "")}"
        fun source() = PGSimpleDataSource().apply {
            setURL(System.getenv("GROMOZEKA_POSTGRES_URL") ?: "jdbc:postgresql://localhost:5432/gromozeka")
            user = System.getenv("GROMOZEKA_POSTGRES_USER") ?: "gromozeka"
            password = System.getenv("GROMOZEKA_POSTGRES_PASSWORD") ?: "gromozeka"
        }
        val admin = source()
        admin.connection.use { it.createStatement().use { it.execute("CREATE SCHEMA $schema") } }
        try {
            val database = source().apply { currentSchema = schema }
            database.connection.use { it.createStatement().use { sql ->
                sql.execute("CREATE TABLE users(id VARCHAR(255) PRIMARY KEY)")
                sql.execute("INSERT INTO users VALUES ('alice'),('bob')")
                sql.execute("CREATE TABLE conversations(id VARCHAR(255) PRIMARY KEY)")
                sql.execute("INSERT INTO conversations VALUES ('source')")
                sql.execute(requireNotNull(javaClass.classLoader.getResource("db/migration/postgres/V76__user_notifications.sql")).readText())
            } }
            val repo = PostgresUserNotificationRepository(database)
            val at = Instant.parse("2026-01-01T00:00:00Z")
            val alice = User.Id("alice")
            val bob = User.Id("bob")
            val draft = UserNotificationDraft("Title", "Message", conversationId = Conversation.Id("source"), sourceKey = "producer:event")
            val published = listOf(async(Dispatchers.IO) { repo.publish(alice, draft, at) },
                async(Dispatchers.IO) { repo.publish(alice, draft, at) }).awaitAll()
            assertEquals(1, published.count { it.created })
            assertEquals(1, published.map { it.notification.id }.distinct().size)
            val notice = published.first().notification
            assertFailsWith<IllegalStateException> { repo.publish(alice, draft.copy(message = "changed"), at) }
            repo.publish(bob, draft, at)
            repo.publish(alice, draft.copy(sourceKey = "producer:second"), at)
            repo.publish(alice, draft.copy(sourceKey = null), at)
            val restarted = PostgresUserNotificationRepository(database)
            assertEquals(3L, restarted.summary(alice).unreadCount)
            assertEquals(1L, restarted.summary(bob).unreadCount)
            val seen = mutableSetOf<UserNotification.Id>()
            var cursor: UserNotificationCursor? = null
            do {
                val page = restarted.list(alice, cursor, 1, false)
                assertEquals(1, page.items.size)
                assertTrue(seen.add(page.items.single().id))
                assertEquals(alice, page.items.single().userId)
                cursor = page.nextCursor
            } while (cursor != null)
            assertEquals(3, seen.size)
            assertFailsWith<UserNotificationNotFoundException> { restarted.setRead(bob, notice.id, true, at) }
            assertFalse(restarted.delete(bob, notice.id))
            assertEquals(at, restarted.setRead(alice, notice.id, true, at).readAt)
            assertEquals(2, restarted.markAllRead(alice, at))
            assertEquals(0L, restarted.summary(alice).unreadCount)
            assertEquals(1L, restarted.summary(bob).unreadCount)
            assertTrue(restarted.list(alice, null, 50, true).items.isEmpty())
            restarted.setRead(alice, notice.id, false, at)
            assertEquals(listOf(notice.id), restarted.list(alice, null, 50, true).items.map { it.id })
            database.connection.use { it.createStatement().use { it.execute("DELETE FROM conversations WHERE id='source'") } }
            assertEquals(3, restarted.list(alice, null, 50, false).items.size)
            assertTrue(restarted.delete(alice, notice.id))
            assertFalse(restarted.delete(alice, notice.id))
            assertFalse(restarted.publish(alice, draft, at).created)
            assertFalse(restarted.list(alice, null, 50, false).items.any { it.id == notice.id })
            assertFailsWith<UserNotificationNotFoundException> { restarted.setRead(alice, notice.id, false, at) }
            assertEquals(0L, restarted.summary(alice).unreadCount)
        } finally {
            admin.connection.use { it.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") } }
        }
    }
}
