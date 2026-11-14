package com.gromozeka.server

import com.gromozeka.application.service.*
import com.gromozeka.domain.model.*
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.domain.repository.ThreadMessageLink
import com.gromozeka.domain.repository.MessageRepository
import com.gromozeka.domain.repository.ArtifactRepository
import com.gromozeka.domain.service.*
import com.gromozeka.infrastructure.db.persistence.*
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.mockito.Mockito
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.sql.Connection
import java.sql.PreparedStatement
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

/** Real migrated PostgreSQL and independent local files; unrelated application services are not started. */
@EnabledIfEnvironmentVariable(named = "GROMOZEKA_POSTGRES_RUNTIME_TEST", matches = "true")
class PostgresConversationForkTest {
    @Test fun `large fork preserves history coverage artifacts search and independent ownership with bounded database calls`() = runBlocking<Unit> {
        database { f ->
            val history = f.seed(messageCount = 8000, artifactCount = 3500)
            val sourceArtifacts = f.artifacts.findByConversation(f.source.id)
            f.observed.reset()
            val start = System.nanoTime()
            val fork = f.service.fork(f.source.id)
            val elapsed = (System.nanoTime() - start) / 1_000_000
            val executions = f.observed.executions.get()
            val transactions = f.observed.commits.get()
            println("FORK_COPY messages=${history.size} artifacts=${sourceArtifacts.size} elapsed_ms=$elapsed jdbc_executions=$executions commits=$transactions")
            assertTrue(executions < 250, "Fork must use chunked JDBC batches, not per-message/artifact calls: $executions")
            assertTrue(transactions <= 12, "Fork must not open a transaction for each item: $transactions")
            val copied = f.links.getMessagesByThread(fork.currentThread)
            val copiedArtifacts = f.artifacts.findByConversation(fork.id)
            assertEquals(history.size, copied.size)
            assertEquals(sourceArtifacts.size, copiedArtifacts.size)
            assertTrue(copied.all { it.conversationId == fork.id })
            assertTrue(copied.map { it.id }.toSet().intersect(history.map { it.id }.toSet()).isEmpty())
            assertTrue(copiedArtifacts.all { it.conversationId == fork.id && it.state == Artifact.State.COMMITTED })
            assertTrue(copiedArtifacts.map { it.id }.toSet().intersect(sourceArtifacts.map { it.id }.toSet()).isEmpty())
            val remap = history.zip(copied).associate { (old, new) -> old.id to new.id }
            val byName = copiedArtifacts.associateBy { it.fileName }
            for ((old, new) in history.zip(copied)) {
                assertEquals(old.originalIds, new.originalIds)
                assertEquals(old.providerMetadata, new.providerMetadata)
                assertEquals(old.instructions, new.instructions)
                assertEquals(old.role, new.role)
                val oldCheckpoint = old.content.singleOrNull() as? ContentItem.ContextCompactionResult
                if (oldCheckpoint != null) {
                    val checkpoint = new.content.single() as ContentItem.ContextCompactionResult
                    assertEquals(oldCheckpoint.sourceMessageIds.map { remap.getValue(it) }, checkpoint.sourceMessageIds)
                    assertEquals(oldCheckpoint.invalidatedReplayMessageIds.map { remap.getValue(it) }, checkpoint.invalidatedReplayMessageIds)
                    assertEquals(oldCheckpoint.payload, checkpoint.payload)
                    assertNull(checkpoint.historyTransition)
                } else if (old.content.singleOrNull() is ContentItem.ToolResult) {
                    val oldResult = old.content.single() as ContentItem.ToolResult
                    val result = new.content.single() as ContentItem.ToolResult
                    val oldRef = (oldResult.result.single() as ContentItem.ToolResult.Data.ArtifactData).artifact
                    val newRef = (result.result.single() as ContentItem.ToolResult.Data.ArtifactData).artifact
                    assertEquals(oldResult.toolUseId, result.toolUseId)
                    assertEquals(byName.getValue(oldRef.fileName).reference(), newRef)
                } else assertEquals(old.content, new.content)
            }
            assertEquals(ConversationContext(history).protectedMessageIds().map { remap.getValue(it) }.toSet(),
                ConversationContext(copied).protectedMessageIds())
            assertEquals(ConversationContext(history).messages().map { remap.getValue(it.id) }, ConversationContext(copied).messages().map { it.id })
            val copiedPins = f.sql.queryForList("SELECT text FROM conversation_pins WHERE conversation_id=? ORDER BY ordinal", String::class.java, fork.id.value)
            assertEquals(listOf("Synthetic pin"), copiedPins)
            val toolMessage = copied.first { it.content.singleOrNull() is ContentItem.ToolResult }
            val search = f.sql.queryForObject("SELECT search_text FROM messages WHERE id=?", String::class.java, toolMessage.id.value)
            assertContains(requireNotNull(search), "TOOL_SEARCH_MARKER")
            assertNotNull(f.sql.queryForObject("SELECT context_metadata_json FROM messages WHERE id=?", String::class.java, copied.last().id.value))
            assertEquals(history, f.links.getMessagesByThread(f.source.currentThread), "Source history is not rewritten")
            val original = sourceArtifacts.first()
            val clone = byName.getValue(original.fileName)
            assertContentEquals(f.store.read(original.id), f.store.read(clone.id))
            assertFailsWith<IllegalArgumentException> { f.artifactService.materialize(f.source.id, listOf(toolMessage)) }
            f.store.delete(original.id)
            assertContentEquals(f.bytes, f.store.read(clone.id))
        }
    }

    @Test fun `message and artifact batches roll back across chunk boundaries and preserve missing ID ordering`() = runBlocking<Unit> {
        database { f ->
            val history = f.seed(messageCount = 640, artifactCount = 300)
            val target = f.newConversation("batch-target")
            val copies = history.mapIndexed { index, message -> message.copy(id = Conversation.Message.Id("copy-$index"), conversationId = target.id) }
            f.messages.save(copies.last())
            assertFails { f.messages.saveBatch(copies) }
            assertEquals(listOf(copies.last()), f.messages.findByConversation(target.id), "An error in a later batch must roll back earlier inserts")
            val selected = history.take(300).reversed().map { it.id }
            assertEquals(selected, f.messages.findByIds(selected + Conversation.Message.Id("missing") + selected.first()).map { it.id })
            val sourceArtifacts = f.artifacts.findByConversation(f.source.id)
            val ids = sourceArtifacts.associate { it.id to Artifact.Id(UUID.randomUUID().toString()) }
            val invalid = ids + (Artifact.Id("missing-source") to Artifact.Id(UUID.randomUUID().toString()))
            assertFailsWith<IllegalArgumentException> { f.artifacts.cloneBatch(f.source.id, target, invalid, f.now) }
            assertTrue(f.artifacts.findByConversation(target.id).isEmpty())
            assertFailsWith<IllegalArgumentException> { f.artifacts.cloneBatch(target.id, f.source, ids, f.now) }
            assertFailsWith<IllegalArgumentException> { f.artifacts.cloneBatch(f.source.id, target.copy(projectId = Project.Id("wrong-project")), ids, f.now) }
            val cloned = f.artifacts.cloneBatch(f.source.id, target, ids, f.now)
            assertEquals(ids.size, cloned.size)
            assertFailsWith<IllegalArgumentException> { f.artifacts.commit(cloned.map { it.id } + Artifact.Id("missing"), f.now) }
            assertTrue(f.artifacts.findByConversation(target.id).all { it.state == Artifact.State.DRAFT })
            f.artifacts.commit(cloned.map { it.id }, f.now)
            assertTrue(f.artifacts.findByConversation(target.id).all { it.state == Artifact.State.COMMITTED })
        }
    }

    @Test fun `batching reduces database round trips for the same independent copy workload`() = runBlocking<Unit> {
        database { f ->
            f.seed(messageCount = 640, artifactCount = 300)
            val singleMessages = object : MessageRepository by f.messages {
                override suspend fun saveBatch(messages: List<Conversation.Message>) { messages.forEach { f.messages.save(it) } }
            }
            val singleArtifacts = object : ArtifactRepository by f.artifacts {
                override suspend fun cloneBatch(sourceConversationId: Conversation.Id, targetConversation: Conversation,
                    sourceToTargetIds: Map<Artifact.Id, Artifact.Id>, createdAt: kotlin.time.Instant): List<Artifact> =
                    sourceToTargetIds.flatMap { (source, target) -> f.artifacts.cloneBatch(sourceConversationId, targetConversation, mapOf(source to target), createdAt) }
                override suspend fun commit(ids: List<Artifact.Id>, committedAt: kotlin.time.Instant) {
                    ids.forEach { f.artifacts.commit(listOf(it), committedAt) }
                }
            }
            val observations = mutableMapOf<String, Pair<Int, Int>>()
            // Both variants use the same native file copy. This isolates batching, not an
            // assertion about end-to-end timing on a user's Server or the old upload path.
            for ((name, service) in listOf("single-item" to f.forkService(singleMessages, singleArtifacts), "batched" to f.service)) {
                f.observed.reset()
                val start = System.nanoTime()
                val fork = service.fork(f.source.id)
                val millis = (System.nanoTime() - start) / 1_000_000
                observations[name] = f.observed.executions.get() to f.observed.commits.get()
                println("FORK_BATCH_COMPARISON mode=$name messages=640 artifacts=300 elapsed_ms=$millis jdbc_executions=${f.observed.executions.get()} commits=${f.observed.commits.get()}")
                assertEquals(640, f.links.getByThread(fork.currentThread).size)
                assertEquals(300, f.artifacts.findByConversation(fork.id).size)
            }
            assertTrue(observations.getValue("batched").first * 10 < observations.getValue("single-item").first)
            assertTrue(observations.getValue("batched").second * 10 < observations.getValue("single-item").second)
        }
    }

    private suspend fun database(block: suspend (Fixture) -> Unit) {
        val url = requireNotNull(System.getenv("GROMOZEKA_POSTGRES_URL"))
        val user = requireNotNull(System.getenv("GROMOZEKA_POSTGRES_USER"))
        val password = requireNotNull(System.getenv("GROMOZEKA_POSTGRES_PASSWORD"))
        val schema = "fork_${UUID.randomUUID().toString().replace("-", "")}"
        val admin = DriverManagerDataSource(url, user, password)
        val home = Files.createTempDirectory("conversation-fork-test-").toFile()
        var pool: HikariDataSource? = null
        try {
            Flyway.configure().dataSource(admin).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration/postgres").load().migrate()
            pool = HikariDataSource(HikariConfig().apply {
                jdbcUrl = url; username = user; this.password = password; maximumPoolSize = 2
                connectionInitSql = "SET search_path TO \"$schema\", public"
            })
            val observed = ObservedDataSource(pool)
            Database.connect(observed)
            val settings = Mockito.mock(SettingsService::class.java)
            Mockito.`when`(settings.gromozekaHome).thenReturn(home)
            val f = Fixture(pool, observed, LocalArtifactContentStore(settings))
            f.initialize()
            block(f)
        } finally {
            pool?.close()
            JdbcTemplate(admin).execute("DROP SCHEMA IF EXISTS $schema CASCADE")
            assertTrue(home.deleteRecursively())
        }
    }

    private class Fixture(val database: DataSource, val observed: ObservedDataSource, val store: LocalArtifactContentStore) {
        val now = Clock.System.now()
        val sql = JdbcTemplate(database)
        val messages = ExposedMessageRepository(Json)
        val links = ExposedThreadMessageRepository(Json)
        val artifacts = ExposedArtifactRepository()
        val conversations = ExposedConversationRepository()
        val threads = ExposedThreadRepository()
        val artifactService = ConversationArtifactApplicationService(artifacts, store)
        val bytes = ("TOOL_SEARCH_MARKER\n" + "x".repeat(16_000)).encodeToByteArray()
        val project = Project(Project.Id("project"), "Test", createdAt = now, lastUsedAt = now)
        lateinit var source: Conversation
        val service get() = forkService()
        fun forkService(messages: MessageRepository = this.messages, artifacts: ArtifactRepository = this.artifacts) =
            ConversationApplicationService(conversations, threads, messages, links, mock(), mock(), mock(),
                ToolCallPairingService(), mock(), mock(), ConversationArtifactApplicationService(artifacts, store), mock(), mock())

        suspend fun initialize() {
            ExposedProjectRepository().save(project)
            source = newConversation("source")
            sql.update("INSERT INTO conversation_pins(id,conversation_id,text,ordinal,revision,created_at,updated_at) VALUES ('pin',?,'Synthetic pin',0,1,now(),now())", source.id.value)
        }
        suspend fun newConversation(id: String): Conversation {
            val conversation = Conversation(Conversation.Id(id), project.id, emptySet(), displayName = id,
                currentThread = Conversation.Thread.Id("thread-$id"), createdAt = now, updatedAt = now)
            conversations.create(conversation)
            threads.save(Conversation.Thread(conversation.currentThread, conversation.id, createdAt = now, updatedAt = now))
            return conversation
        }
        suspend fun seed(messageCount: Int, artifactCount: Int): List<Conversation.Message> {
            require(messageCount >= artifactCount * 2 + 3)
            val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val media = List(artifactCount) { index -> Artifact(Artifact.Id(UUID.randomUUID().toString()), project.id, source.id, null,
                "output-$index.bin", "text/plain", bytes.size.toLong(), Artifact.ContentSource.Managed(sha), Artifact.Purpose.TOOL_OUTPUT,
                Artifact.State.COMMITTED, now, now) }
            database.connection.use { connection ->
                connection.prepareStatement("INSERT INTO artifacts(id,project_id,conversation_id,file_name,media_type,size_bytes,purpose,state,created_at,committed_at,content_source,search_text) VALUES (?,?,?,?,?,?,?,'COMMITTED',now(),now(),?,?)").use { s ->
                    media.chunked(256).forEach { batch ->
                        batch.forEach { artifact ->
                            s.setString(1, artifact.id.value); s.setString(2, project.id.value); s.setString(3, source.id.value)
                            s.setString(4, artifact.fileName); s.setString(5, artifact.mediaType); s.setLong(6, bytes.size.toLong())
                            s.setString(7, artifact.purpose.name); s.setString(8, Json.encodeToString(artifact.source)); s.setString(9, bytes.decodeToString()); s.addBatch()
                        }
                        s.executeBatch()
                    }
                }
            }
            media.forEach { store.write(it.id, bytes) }
            val history = (0 until messageCount - 1).map { i ->
                val content = when {
                    i < artifactCount * 2 && i % 2 == 0 -> ContentItem.ToolCall(ContentItem.ToolCall.Id("call-${i / 2}"), ContentItem.ToolCall.Data("tool", buildJsonObject {}))
                    i < artifactCount * 2 -> ContentItem.ToolResult(ContentItem.ToolCall.Id("call-${i / 2}"), "tool", listOf(ContentItem.ToolResult.Data.ArtifactData(media[i / 2].reference())))
                    else -> ContentItem.UserMessage("Message $i: " + "context ".repeat(1250))
                }
                Conversation.Message(Conversation.Message.Id("m-$i"), source.id, role = if (content is ContentItem.ToolCall) Conversation.Message.Role.ASSISTANT else Conversation.Message.Role.USER,
                    content = listOf(content), createdAt = now + i.milliseconds,
                    providerMetadata = buildJsonObject { put("synthetic-test", true) })
            }.toMutableList()
            history += Conversation.Message(Conversation.Message.Id("checkpoint"), source.id, role = Conversation.Message.Role.ASSISTANT,
                content = listOf(ContentItem.ContextCompactionResult(ContentItem.ContextCompactionResult.Payload.ReadableSummary("Summary"),
                    ContentItem.ContextCompactionResult.Origin.GROMOZEKA_POLICY, sourceMessageIds = history.map { it.id },
                    invalidatedReplayMessageIds = listOf(history.first().id), coverage = ContentItem.ContextCompactionResult.Coverage.ALL_PREVIOUS,
                    historyTransition = ContentItem.ContextCompactionResult.HistoryTransition(Conversation.Thread.Id("old"), source.currentThread))), createdAt = now)
            messages.saveBatch(history)
            links.addBatch(history.mapIndexed { index, message -> ThreadMessageLink(source.currentThread, message.id, index) })
            return history
        }
    }

    private class ObservedDataSource(private val delegate: DataSource) : DataSource by delegate {
        val executions = AtomicInteger()
        val commits = AtomicInteger()
        fun reset() { executions.set(0); commits.set(0) }
        override fun getConnection(): Connection = observe(delegate.connection)
        override fun getConnection(user: String, password: String): Connection = observe(delegate.getConnection(user, password))
        private fun observe(connection: Connection): Connection = wrap(connection) { name, value ->
            if (name == "commit") commits.incrementAndGet()
            if (name == "prepareStatement") wrap(value as PreparedStatement) { method, result ->
                if (method in setOf("execute", "executeQuery", "executeUpdate", "executeBatch", "executeLargeBatch")) executions.incrementAndGet()
                result
            } else value
        }
        private inline fun <reified T> wrap(target: T, crossinline after: (String, Any?) -> Any?): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
                val result = try { method.invoke(target, *(args ?: emptyArray())) } catch (e: InvocationTargetException) { throw e.targetException }
                after(method.name, result)
            } as T
    }

    private companion object {
        inline fun <reified T> mock(): T = Mockito.mock(T::class.java)
    }
}
