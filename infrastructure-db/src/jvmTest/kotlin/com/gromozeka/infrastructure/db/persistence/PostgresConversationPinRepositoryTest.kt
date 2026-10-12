package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.*
import kotlinx.coroutines.*
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.Assume.assumeTrue
import org.postgresql.ds.PGSimpleDataSource
import java.util.UUID
import kotlin.test.*
import kotlin.time.Instant

/** Real PostgreSQL transactions, constraints, durable receipts and concurrent writers. Never a fake atomicity test. */
class PostgresConversationPinRepositoryTest {
    private val actor = User.Id("actor")
    private val now = Instant.fromEpochMilliseconds(123)
    private fun conversation(id: String, project: String = "project") = Conversation(Conversation.Id(id), Project.Id(project),
        setOf(Conversation.Participant.User(actor)), currentThread = Conversation.Thread.Id("$id-thread"), createdAt = now, updatedAt = now)

    @Test fun `CRUD preserves exact text metadata source independence CAS and durable creation receipts`() = runBlocking<Unit> {
        withDatabase { source ->
            val conversations = ExposedConversationRepository(); val pins = ExposedConversationPinRepository()
            val owner = conversations.create(conversation("owner")).id
            val other = conversations.create(conversation("other")).id
            val provenance = ConversationPinSource(Conversation.Id("deleted-or-unknown-source"), Conversation.Message.Id("never-dereferenced"))
            val text = "  שלום\r\nКириллица 👩🏽‍💻 100%\t "
            val first = pins.create(owner, actor, text, provenance, "key")
            assertTrue(first.created); assertEquals(1L, first.pin.revision); assertEquals(text, first.pin.text)
            assertEquals(text, ExposedConversationPinRepository().get(owner, first.pin.id)?.text)
            val replay = ExposedConversationPinRepository().create(owner, actor, text, provenance, "key")
            assertFalse(replay.created); assertEquals(first.pin, replay.pin)
            assertFailsWith<ConversationPinConflictException> { pins.create(owner, actor, "changed payload", provenance, "key") }
            assertFailsWith<ConversationPinConflictException> { pins.create(owner, actor, text, null, "key") }
            val changed = pins.update(owner, first.pin.id, 1, " edited\n")
            assertEquals(first.pin.source, changed.source); assertEquals(first.pin.ordinal, changed.ordinal)
            assertEquals(2L, changed.revision); assertEquals(first.pin.createdAt, changed.createdAt)
            assertEquals(changed, pins.update(owner, changed.id, 2, changed.text), "A same-text CAS is a no-op")
            assertEquals(changed, pins.create(owner, actor, text, provenance, "key").pin)
            assertFailsWith<ConversationPinConflictException> { pins.update(owner, first.pin.id, 1, "stale") }
            assertFailsWith<ConversationPinConflictException> { pins.delete(owner, first.pin.id, 1) }
            assertFailsWith<ConversationPinNotFoundException> { pins.update(other, first.pin.id, 2, "cross owner") }
            assertNull(pins.get(other, first.pin.id))
            val secondActor = pins.create(owner, User.Id("actor-2"), text, provenance, "key").pin
            assertNotEquals(first.pin.id, secondActor.id)
            val secondTarget = pins.create(other, actor, text, provenance, "key").pin
            assertNotEquals(first.pin.id, secondTarget.id)
            pins.delete(owner, first.pin.id, 2)
            assertNull(pins.get(owner, first.pin.id))
            assertFailsWith<ConversationPinNotFoundException> { ExposedConversationPinRepository().create(owner, actor, text, provenance, "key") }
            assertEquals(listOf(secondActor), pins.list(owner))
            assertTrue(first.pin.ordinal < secondActor.ordinal)
            conversations.delete(owner)
            assertTrue(pins.list(owner).isEmpty())
            assertEquals(0, queryCount(source, "SELECT count(*) FROM conversation_pin_creations WHERE conversation_id='owner'"))
            assertNotNull(pins.get(other, secondTarget.id))
        }
    }

    @Test fun updateReturnsPersistedRowForCanonicalTimestamp() = runBlocking<Unit> {
        withDatabase { source ->
            val conversations = ExposedConversationRepository(); val pins = ExposedConversationPinRepository()
            val owner = conversations.create(conversation("canonical-update")).id
            val created = pins.create(owner, actor, "before", creationKey = "canonical-key").pin
            val canonical = Instant.parse("2001-02-03T04:05:06.123456Z")
            // Force DB-side timestamp canonicalization independently of the host clock resolution.
            // This proves the persisted-row return contract; it is not a replay of a Linux CI trace.
            sql(source, """CREATE FUNCTION canonical_pin_timestamp() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
                BEGIN NEW.updated_at = TIMESTAMPTZ '2001-02-03T04:05:06.123456Z'; RETURN NEW; END ${'$'}${'$'};
                CREATE TRIGGER canonical_timestamp BEFORE UPDATE ON conversation_pins FOR EACH ROW
                EXECUTE FUNCTION canonical_pin_timestamp();""")
            val changed = pins.update(owner, created.id, created.revision, " after\n")
            val stored = assertNotNull(ExposedConversationPinRepository().get(owner, created.id))
            assertEquals(canonical, stored.updatedAt)
            assertEquals(stored, changed, "Mutation response must be the canonical persisted row")
            assertEquals(created.createdAt, changed.createdAt)
            assertEquals(created.ordinal, changed.ordinal)
            assertEquals(2L, changed.revision)
            assertEquals(" after\n", changed.text)
            assertEquals(changed, pins.update(owner, changed.id, changed.revision, changed.text))
            assertEquals(changed, ExposedConversationPinRepository().create(owner, actor, "before", creationKey = "canonical-key").pin)
            assertEquals(listOf(changed), pins.list(owner))
            assertFailsWith<ConversationPinConflictException> { pins.update(owner, changed.id, created.revision, changed.text) }
            assertEquals(changed, pins.get(owner, changed.id))
        }
    }

    @Test fun `UTF16 text count and total limits are enforced inside the transaction`() = runBlocking<Unit> {
        withDatabase { _ ->
            val conversations = ExposedConversationRepository(); val pins = ExposedConversationPinRepository()
            val total = conversations.create(conversation("total")).id
            repeat(3) { pins.create(total, actor, "x".repeat(32_000)) }
            val emoji = pins.create(total, actor, "😀".repeat(16_000)).pin
            assertEquals(128_000, pins.list(total).sumOf { it.text.length })
            assertFailsWith<ConversationPinLimitException> { pins.create(total, actor, "a") }
            assertFailsWith<ConversationPinLimitException> { pins.create(total, actor, "😀".repeat(16_000) + "a") }
            assertFailsWith<IllegalArgumentException> { pins.create(total, actor, " \r\n") }
            val small = pins.update(total, emoji.id, emoji.revision, "😀")
            val tail = pins.create(total, actor, "x".repeat(31_998)).pin
            assertFailsWith<ConversationPinLimitException> { pins.update(total, small.id, small.revision, "😀x") }
            assertEquals(tail, pins.get(total, tail.id))
            assertEquals(small, pins.get(total, small.id))
            val count = conversations.create(conversation("count")).id
            repeat(100) { pins.create(count, actor, "x") }
            assertFailsWith<ConversationPinLimitException> { pins.create(count, actor, "x") }
            assertEquals(100, pins.list(count).size)
        }
    }

    @Test fun `copy is ordered independent and failure rolls back the Conversation and entire catalog`() = runBlocking<Unit> {
        withDatabase { source ->
            val conversations = ExposedConversationRepository(); val pins = ExposedConversationPinRepository()
            val empty = conversations.create(conversation("empty"))
            val emptyCopy = conversations.createWithCopiedPins(conversation("empty-copy"), empty.id)
            assertTrue(pins.list(emptyCopy.id).isEmpty())
            val original = conversations.create(conversation("original"))
            val a = pins.create(original.id, actor, "A", ConversationPinSource(original.id, Conversation.Message.Id("old-message")), "a").pin
            val b = pins.create(original.id, actor, "B", creationKey = "b").pin
            val edited = pins.update(original.id, a.id, 1, "A edited")
            val copy = conversations.createWithCopiedPins(conversation("copy"), original.id)
            val copied = pins.list(copy.id)
            assertEquals(listOf("A edited", "B"), copied.map { it.text })
            assertEquals(listOf(edited.ordinal, b.ordinal), copied.map { it.ordinal })
            assertTrue(copied.all { it.revision == 1L && it.conversationId == copy.id })
            assertTrue(copied.none { it.id == a.id || it.id == b.id })
            assertEquals(edited.source, copied.first().source)
            assertEquals(0, queryCount(source, "SELECT count(*) FROM conversation_pin_creations WHERE conversation_id='copy'"))
            pins.delete(original.id, b.id, 1); pins.update(original.id, edited.id, 2, "changed again")
            assertEquals(copied, pins.list(copy.id))
            conversations.updateCurrentThread(original.id, Conversation.Thread.Id("after-reset-or-compact"))
            assertEquals("changed again", pins.list(original.id).single().text)
            sql(source, """CREATE FUNCTION fail_pin_copy() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
                BEGIN RAISE EXCEPTION 'synthetic pin insert failure'; END ${'$'}${'$'};
                CREATE TRIGGER fail_copy BEFORE INSERT ON conversation_pins FOR EACH ROW
                WHEN (NEW.conversation_id = 'failed-copy') EXECUTE FUNCTION fail_pin_copy();""")
            assertFails { conversations.createWithCopiedPins(conversation("failed-copy"), original.id) }
            assertNull(conversations.findById(Conversation.Id("failed-copy")))
            assertTrue(pins.list(Conversation.Id("failed-copy")).isEmpty())
            assertEquals(0, queryCount(source, "SELECT count(*) FROM conversation_user_participants WHERE conversation_id='failed-copy'"))
            assertFailsWith<ConversationPinNotFoundException> { conversations.createWithCopiedPins(conversation("missing-copy"), Conversation.Id("missing")) }
            assertNull(conversations.findById(Conversation.Id("missing-copy")))
            assertFailsWith<IllegalArgumentException> { conversations.createWithCopiedPins(conversation("wrong-project", "other-project"), original.id) }
            assertNull(conversations.findById(Conversation.Id("wrong-project")))
            conversations.delete(original.id)
            assertEquals(copied, pins.list(copy.id))
        }
    }

    @Test fun `concurrent retries and copy serialize on the same owner row`() = runBlocking<Unit> {
        withDatabase { _ ->
            val conversations = ExposedConversationRepository(); val pins = ExposedConversationPinRepository()
            val owner = conversations.create(conversation("concurrent")).id
            val results = coroutineScope {
                (1..12).map { async(Dispatchers.IO) { ExposedConversationPinRepository().create(owner, actor, "once", creationKey = "same") } }.awaitAll()
            }
            assertEquals(1, results.count { it.created }); assertEquals(1, results.map { it.pin.id }.distinct().size)
            assertEquals(1, pins.list(owner).size)
            val original = pins.list(owner).single()
            val (copy, update) = coroutineScope {
                val copy = async(Dispatchers.IO) { conversations.createWithCopiedPins(conversation("concurrent-copy"), owner) }
                val update = async(Dispatchers.IO) { pins.update(owner, original.id, 1, "updated") }
                copy.await() to update.await()
            }
            val copied = pins.list(copy.id).single()
            assertTrue(copied.text == original.text || copied.text == update.text)
            assertEquals(1L, copied.revision)
            assertNotEquals(original.id, copied.id)
            val limitOwner = conversations.create(conversation("concurrent-limit")).id
            repeat(98) { pins.create(limitOwner, actor, "x") }
            val appended = coroutineScope {
                (1..8).map { async(Dispatchers.IO) {
                    try { pins.create(limitOwner, actor, "x"); true } catch (_: ConversationPinLimitException) { false }
                } }.awaitAll()
            }
            assertEquals(2, appended.count { it }); assertEquals(100, pins.list(limitOwner).size)
        }
    }

    private suspend fun withDatabase(block: suspend (PGSimpleDataSource) -> Unit) {
        assumeTrue("Requires a separately authorized PostgreSQL test window", System.getenv("GROMOZEKA_POSTGRES_RUNTIME_TEST") == "true")
        val schema = "pins_test_${UUID.randomUUID().toString().replace("-", "") }"
        val jdbcUrl = requireNotNull(System.getenv("GROMOZEKA_POSTGRES_URL")) { "Explicit GROMOZEKA_POSTGRES_URL is required" }
        val jdbcUser = requireNotNull(System.getenv("GROMOZEKA_POSTGRES_USER")) { "Explicit GROMOZEKA_POSTGRES_USER is required" }
        val jdbcPassword = requireNotNull(System.getenv("GROMOZEKA_POSTGRES_PASSWORD")) { "Explicit GROMOZEKA_POSTGRES_PASSWORD is required (empty is allowed)" }
        require(jdbcUrl.isNotBlank() && jdbcUser.isNotBlank()) { "Explicit PostgreSQL URL and user must not be blank" }
        val source = PGSimpleDataSource().apply {
            setURL(jdbcUrl)
            user = jdbcUser
            password = jdbcPassword
            currentSchema = "$schema,public"
        }
        try {
            fun flyway() = Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).locations("classpath:db/migration/postgres")
            flyway().target("71").load().migrate()
            sql(source, """INSERT INTO users(id,display_name,status,role,created_at,updated_at) VALUES ('actor','Actor','ACTIVE','MEMBER',now(),now()),('actor-2','Other','ACTIVE','MEMBER',now(),now());
                INSERT INTO projects(id,name,created_at,last_used_at) VALUES ('project','Project',now(),now()),('other-project','Other',now(),now());""")
            flyway().load().migrate()
            Database.connect(source)
            block(source)
        } finally {
            sql(source, "DROP SCHEMA IF EXISTS $schema CASCADE")
        }
    }

    private fun sql(source: PGSimpleDataSource, sql: String) {
        source.connection.use { c -> c.createStatement().use { it.execute(sql) } }
    }
    private fun queryCount(source: PGSimpleDataSource, sql: String): Int = source.connection.use { c ->
        c.createStatement().use { s -> s.executeQuery(sql).use { r -> check(r.next()); r.getInt(1) } }
    }
}
