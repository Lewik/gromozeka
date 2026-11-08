package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.UserIdentity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.Assume.assumeTrue
import org.postgresql.ds.PGSimpleDataSource
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import javax.sql.DataSource
import kotlin.test.*
import kotlin.time.Clock

class PostgresRuntimeMessageReadTest {
    @Test
    fun `point reads preserve full messages and author projection while isolating threads`() = runBlocking {
        withFixture { f ->
            val current = f.conversation.currentThread
            val old = Conversation.Thread.Id("old")
            val otherConversation = f.conversation.copy(id = Conversation.Id("other"), currentThread = Conversation.Thread.Id("foreign"))
            ExposedConversationRepository().create(otherConversation)
            ExposedThreadRepository().save(Conversation.Thread(old, f.conversation.id, createdAt = f.now, updatedAt = f.now))
            ExposedThreadRepository().save(Conversation.Thread(otherConversation.currentThread, otherConversation.id, createdAt = f.now, updatedAt = f.now))
            val target = f.message("target").copy(
                author = Conversation.Message.Author.User(f.owner.id, "Stored name"),
                providerMetadata = buildJsonObject { put("opaque-replay", "synthetic-signed-state") },
                content = listOf(ContentItem.ToolResult(ContentItem.ToolCall.Id("call"), "tool",
                    listOf(ContentItem.ToolResult.Data.Text("x".repeat(80_000))))),
            )
            f.save(target, current, 0)
            f.links.add(old, target.id, 0)
            f.save(f.message("old-only"), old, 1)
            f.save(f.message("foreign-only").copy(conversationId = otherConversation.id), otherConversation.currentThread, 0)
            f.save(f.message("malformed"), current, 1)
            f.sql("UPDATE messages SET message_json = 'INVALID_ARCHIVE_JSON' WHERE id = 'malformed'")
            val expected = target.copy(author = Conversation.Message.Author.User(f.owner.id, "Current name"))
            val read = f.measure { assertEquals(expected, f.history.message(current, target.id)?.message) }
            assertEquals(1, read.bodies)
            assertEquals(expected, f.history.message(old, target.id)?.message)
            assertNull(f.history.message(current, Conversation.Message.Id("old-only")))
            assertNull(f.history.message(current, Conversation.Message.Id("foreign-only")))
            assertNull(f.history.message(current, Conversation.Message.Id("missing")))
            assertNull(f.history.message(Conversation.Thread.Id("missing"), target.id))
            val membership = f.measure {
                assertTrue(f.links.contains(current, target.id))
                assertTrue(f.links.contains(current, Conversation.Message.Id("malformed")))
                assertFalse(f.links.contains(current, Conversation.Message.Id("old-only")))
                assertFalse(f.links.contains(current, Conversation.Message.Id("missing")))
                assertEquals(1, f.history.position(current, Conversation.Message.Id("malformed")))
            }
            assertEquals(0, membership.bodies)
            assertFailsWith<kotlinx.serialization.SerializationException> {
                f.history.message(current, Conversation.Message.Id("malformed"))
            }
        }
    }

    @Test
    fun `maximum position is one SQL aggregate and membership reads one link`() = runBlocking {
        withFixture { f ->
            val thread = f.conversation.currentThread
            assertNull(f.links.getMaxPosition(thread))
            assertNull(f.links.getMaxPosition(Conversation.Thread.Id("missing")))
            repeat(40) { f.save(f.message("m-$it"), thread, it) }
            val maximum = f.measure { assertEquals(39, f.links.getMaxPosition(thread)) }
            assertEquals(1, maximum.rows)
            assertEquals(0, maximum.bodies)
            assertEquals(1, maximum.queries.size)
            assertTrue(maximum.queries.single().contains("MAX(", ignoreCase = true))
            val membership = f.measure { assertTrue(f.links.contains(thread, Conversation.Message.Id("m-39"))) }
            assertEquals(1, membership.rows)
            assertEquals(0, membership.bodies)
            assertEquals(1, membership.queries.size)
        }
    }

    @Test
    fun `long compacted history keeps point reads bounded`() = runBlocking {
        withFixture { f ->
            val large = System.getenv("GROMOZEKA_HISTORY_BENCHMARK") == "true"
            val count = if (large) 25_720 else 256
            val thread = f.conversation.currentThread
            val template = f.message("template").copy(content = listOf(ContentItem.UserMessage("x".repeat(10_000))))
            f.source.connection.use { connection ->
                connection.autoCommit = false
                connection.prepareStatement("INSERT INTO messages(id, conversation_id, original_ids_json, role, created_at, search_text, message_json) VALUES (?, ?, '[]', 'USER', now(), '', ?)").use { messages ->
                    connection.prepareStatement("INSERT INTO thread_messages(thread_id, message_id, position) VALUES (?, ?, ?)").use { links ->
                        repeat(count - 1) { i ->
                            val message = template.copy(id = Conversation.Message.Id("archive-$i"))
                            messages.setString(1, message.id.value); messages.setString(2, f.conversation.id.value)
                            messages.setString(3, Json.encodeToString(message)); messages.addBatch()
                            links.setString(1, thread.value); links.setString(2, message.id.value); links.setInt(3, i); links.addBatch()
                            if (i % 128 == 127) { messages.executeBatch(); links.executeBatch() }
                        }
                        messages.executeBatch(); links.executeBatch()
                    }
                }
                connection.commit()
            }
            val summary = f.message("compaction").copy(content = listOf(ContentItem.ContextCompactionResult(
                payload = ContentItem.ContextCompactionResult.Payload.ReadableSummary("Synthetic summary"),
                origin = ContentItem.ContextCompactionResult.Origin.USER_REQUESTED,
                strategy = ContentItem.ContextCompactionResult.Strategy.SUMMARIZE,
                coverage = ContentItem.ContextCompactionResult.Coverage.ALL_PREVIOUS,
            )))
            f.save(summary, thread, count - 1)
            val samples = linkedMapOf<String, MutableList<ReadMeasurement>>()
            suspend fun sample(name: String, operation: suspend () -> Unit): ReadMeasurement =
                f.measure(operation).also { samples.getOrPut(name, ::mutableListOf).add(it) }
            repeat(if (large) 6 else 1) { iteration ->
                val oldFind = sample("full_history_find") {
                    assertEquals(summary, f.links.getMessagesByThread(thread).firstOrNull { it.id == summary.id })
                }
                val find = sample("point_find") { assertEquals(summary, f.history.message(thread, summary.id)?.message) }
                val oldExists = sample("full_history_exists") {
                    assertTrue(f.links.getMessagesByThread(thread).any { it.id == summary.id })
                }
                val exists = sample("point_exists") { assertNotNull(f.history.position(thread, summary.id)) }
                val oldMaximum = sample("all_links_max") { assertEquals(count - 1, f.links.getByThread(thread).maxOfOrNull { it.position }) }
                val maximum = sample("sql_max") { assertEquals(count - 1, f.links.getMaxPosition(thread)) }
                assertEquals(count.toLong(), oldFind.bodies); assertEquals(count.toLong(), oldExists.bodies)
                assertEquals(1, find.bodies); assertEquals(0, exists.bodies)
                assertEquals(count.toLong(), oldMaximum.rows); assertEquals(1, maximum.rows)
                if (large && iteration == 0) samples.values.forEach { it.clear() }
            }
            for ((name, values) in samples) {
                val median = values.map { it.nanos }.sorted()[values.size / 2] / 1_000_000.0
                val counts = values.first()
                println("HISTORY_READ name=$name messages=$count median_ms=$median rows=${counts.rows} bodies=${counts.bodies} body_bytes=${counts.bytes} samples=${values.size}")
            }
        }
    }

    private suspend fun withFixture(test: suspend (Fixture) -> Unit) {
        assumeTrue("Requires explicit isolated PostgreSQL configuration", System.getenv("GROMOZEKA_POSTGRES_RUNTIME_TEST") == "true")
        val schema = "runtime_reads_${UUID.randomUUID().toString().replace("-", "")}"
        val source = PGSimpleDataSource().apply {
            setURL(requireNotNull(System.getenv("GROMOZEKA_POSTGRES_URL")))
            user = requireNotNull(System.getenv("GROMOZEKA_POSTGRES_USER"))
            password = requireNotNull(System.getenv("GROMOZEKA_POSTGRES_PASSWORD"))
            currentSchema = "$schema,public"
        }
        try {
            Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration/postgres").load().migrate()
            val observed = ObservedDataSource(source)
            Database.connect(observed)
            val f = Fixture(source, observed)
            f.initialize()
            test(f)
        } finally {
            source.connection.use { it.createStatement().use { it.execute("DROP SCHEMA IF EXISTS $schema CASCADE") } }
        }
    }

    private class Fixture(val source: PGSimpleDataSource, private val observed: ObservedDataSource) {
        val now = Clock.System.now()
        lateinit var owner: com.gromozeka.domain.model.User
        lateinit var conversation: Conversation
        val links = ExposedThreadMessageRepository(Json)
        val history = PostgresConversationHistoryRepository(observed, Json)
        suspend fun initialize() {
            owner = ExposedIdentityRepository().observeTelegramIdentity(UserIdentity.Telegram(123, "Current name"), now)
            val project = ExposedProjectRepository().save(Project(Project.Id("project"), "Test", createdAt = now, lastUsedAt = now))
            conversation = ExposedConversationRepository().create(Conversation(Conversation.Id("conversation"), project.id,
                setOf(Conversation.Participant.User(owner.id)), currentThread = Conversation.Thread.Id("current"), createdAt = now, updatedAt = now))
            ExposedThreadRepository().save(Conversation.Thread(conversation.currentThread, conversation.id, createdAt = now, updatedAt = now))
        }
        fun message(id: String) = Conversation.Message(Conversation.Message.Id(id), conversation.id,
            role = Conversation.Message.Role.USER, content = listOf(ContentItem.UserMessage(id)), createdAt = now)
        suspend fun save(message: Conversation.Message, thread: Conversation.Thread.Id, position: Int) {
            ExposedMessageRepository(Json).save(message)
            links.add(thread, message.id, position)
        }
        fun sql(sql: String) { source.connection.use { it.createStatement().use { it.execute(sql) } } }
        suspend fun measure(operation: suspend () -> Unit): ReadMeasurement {
            observed.reset()
            val start = System.nanoTime()
            operation()
            return ReadMeasurement(System.nanoTime() - start, observed.rows.get(), observed.bodies.get(), observed.bytes.get(), observed.queries.toList())
        }
    }

    private data class ReadMeasurement(val nanos: Long, val rows: Long, val bodies: Long, val bytes: Long, val queries: List<String>)

    private class ObservedDataSource(private val delegate: DataSource) : DataSource by delegate {
        val rows = AtomicLong(); val bodies = AtomicLong(); val bytes = AtomicLong()
        val queries = CopyOnWriteArrayList<String>()
        fun reset() { rows.set(0); bodies.set(0); bytes.set(0); queries.clear() }
        override fun getConnection(): Connection = connection(delegate.connection)
        override fun getConnection(username: String, password: String): Connection = connection(delegate.getConnection(username, password))
        private fun connection(target: Connection): Connection = wrap(target) { name, args, value ->
            if (name == "prepareStatement") {
                val sql = args[0] as String
                wrap(value as PreparedStatement) { method, _, result ->
                    if (method == "executeQuery") { queries += sql; resultSet(result as ResultSet) } else result
                }
            } else value
        }
        private fun resultSet(target: ResultSet): ResultSet = wrap(target) { name, args, value ->
            if (name == "next" && value == true) rows.incrementAndGet()
            if ((name == "getString" || name == "getObject") && value is String) {
                val column = when (val arg = args[0]) { is Int -> arg; is String -> target.findColumn(arg); else -> error("Unexpected column") }
                if (target.metaData.getColumnLabel(column) == "message_json") {
                    bodies.incrementAndGet(); bytes.addAndGet(value.encodeToByteArray().size.toLong())
                }
            }
            value
        }
        private inline fun <reified T> wrap(target: T, crossinline after: (String, Array<out Any?>, Any?) -> Any?): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, arguments ->
                val args = arguments ?: emptyArray()
                val value = try { method.invoke(target, *args) } catch (failure: InvocationTargetException) { throw failure.targetException }
                after(method.name, args, value)
            } as T
    }
}
