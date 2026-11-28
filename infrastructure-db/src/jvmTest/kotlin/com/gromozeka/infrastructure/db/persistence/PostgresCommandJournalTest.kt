package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.Workspace
import com.gromozeka.domain.model.WorkspaceMount
import com.gromozeka.domain.model.Conversation.Message.ContentItem
import com.gromozeka.domain.service.CommandJournal
import com.gromozeka.domain.service.CommandTask
import com.gromozeka.domain.service.ConversationRuntimeTask
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.junit.Assume
import org.postgresql.ds.PGSimpleDataSource
import java.security.MessageDigest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant

class PostgresCommandJournalTest {
    @Test
    fun `entry call event and content are idempotent durable and searchable`() = runBlocking {
        database { dataSource ->
            val journal = PostgresCommandJournal(dataSource)
            val at = Instant.fromEpochSeconds(100)
            val entry = entry(at)
            val root = rootCall(entry, at)
            val command = content(entry, root, CommandJournal.Content.Kind.COMMAND, "printf 'hello'")
            val arguments = content(entry, root, CommandJournal.Content.Kind.ARGUMENTS, "{\"command\":\"printf 'hello'\"}")

            val admitted = coroutineScope {
                List(8) {
                    async(Dispatchers.Default) { journal.admit(entry, root, listOf(command, arguments)) }
                }.awaitAll()
            }
            assertEquals(List(8) { entry }, admitted)
            assertEquals(entry, journal.admit(entry, root, listOf(command, arguments)))
            assertEquals(entry, journal.findEntry(entry.id))
            assertEquals(entry, journal.findEntryBySource(entry.conversationId, assertNotNull(entry.sourceToolCallId)))
            assertEquals(listOf(root), journal.calls(entry.id))
            assertEquals(setOf(command, arguments), journal.content(entry.id).toSet())

            val child = CommandJournal.Call(
                id = CommandJournal.Call.Id("child-call"),
                entryId = entry.id,
                parentId = root.id,
                kind = CommandJournal.Call.Kind.TOOL,
                toolName = "grz_read_file",
                target = root.target,
                createdAt = at + kotlin.time.Duration.parse("1s"),
            )
            assertEquals(child, journal.append(child))
            assertEquals(child, journal.append(child))

            val started = CommandJournal.Event(
                id = CommandJournal.Event.Id("started-event"),
                entryId = entry.id,
                callId = root.id,
                sourceKey = "command-task:task:started",
                kind = CommandJournal.Event.Kind.STARTED,
                occurredAt = at + kotlin.time.Duration.parse("2s"),
                commandTaskId = CommandTask.Id("task"),
            )
            val output = content(entry, root, CommandJournal.Content.Kind.OUTPUT, "x".repeat(300))
                .copy(eventId = started.id)
            assertEquals(started, journal.append(started, listOf(output)))
            assertEquals(started, journal.append(started, listOf(output)))
            assertEquals(entry, journal.findEntryByCommandTask(CommandTask.Id("task")))
            assertEquals(listOf(started), journal.events(entry.id))

            val search = journal.search(
                CommandJournal.SearchQuery(
                    projectId = entry.projectId,
                    conversationId = entry.conversationId,
                    eventKind = CommandJournal.Event.Kind.STARTED,
                    text = "hello",
                    limit = 1,
                )
            )
            assertEquals(entry.id, search.entries.single().entry.id)
            assertEquals(root.id, search.entries.single().rootCall.id)
            assertEquals(started.id, search.entries.single().latestEvent?.id)
            assertEquals(2, search.entries.single().callCount)
            assertEquals(1, search.entries.single().eventCount)
            assertEquals(null, search.nextCursor)

            assertNull(journal.get(Project.Id("other-project"), entry.id, callLimit = 1, eventLimit = 1))
            val snapshot = assertNotNull(journal.get(entry.projectId, entry.id, callLimit = 1, eventLimit = 1))
            assertEquals(2, snapshot.callCount)
            assertEquals(1, snapshot.eventCount)
            assertEquals(1, snapshot.calls.size)
            assertEquals(true, snapshot.callsTruncated)
            assertEquals(false, snapshot.eventsTruncated)

            assertNull(
                journal.readContent(
                    CommandJournal.ContentQuery(
                        projectId = Project.Id("other-project"),
                        entryId = entry.id,
                    )
                )
            )
            val contentPage = assertNotNull(
                journal.readContent(
                    CommandJournal.ContentQuery(
                        projectId = entry.projectId,
                        entryId = entry.id,
                        kinds = setOf(CommandJournal.Content.Kind.OUTPUT),
                        limit = 1,
                        maxCharactersPerItem = 256,
                    )
                )
            )
            assertEquals(1, contentPage.items.size)
            assertEquals(256, contentPage.items.single().text.length)
            assertEquals(true, contentPage.items.single().textTruncated)
            assertEquals(300, contentPage.items.single().sizeBytes)

            val statistics = journal.statistics(entry.projectId)
            assertEquals(1, statistics.entryCount)
            assertEquals(2, statistics.callCount)
            assertEquals(1, statistics.eventCount)
            assertEquals(3, statistics.contentLinkCount)
            assertEquals(3, statistics.uniqueContentCount)
            assert(statistics.uniqueContentBytes > 0)
            assert(statistics.journalStorageBytes > 0)
            assert(statistics.databaseBytes >= statistics.journalStorageBytes)

            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT count(*) FROM command_journal_contents").use { rows ->
                        rows.next()
                        assertEquals(3, rows.getInt(1))
                    }
                    statement.executeQuery("SELECT count(*) FROM command_journal_content_links").use { rows ->
                        rows.next()
                        assertEquals(3, rows.getInt(1))
                    }
                    statement.executeQuery(
                        """
                        SELECT count(*)
                        FROM pg_constraint constraint_record
                        JOIN pg_class child ON child.oid=constraint_record.conrelid
                        JOIN pg_class parent ON parent.oid=constraint_record.confrelid
                        WHERE child.relname='command_journal_entries'
                          AND parent.relname IN ('conversations','projects','users')
                        """.trimIndent()
                    ).use { rows ->
                        rows.next()
                        assertEquals(0, rows.getInt(1), "Conversation and project deletion must not erase the journal")
                    }
                }
            }
        }
    }

    @Test
    fun `source call content and event identities cannot be rebound`() = runBlocking {
        database { dataSource ->
            val journal = PostgresCommandJournal(dataSource)
            val at = Instant.fromEpochSeconds(100)
            val entry = entry(at)
            val root = rootCall(entry, at)
            val command = content(entry, root, CommandJournal.Content.Kind.COMMAND, "echo original")
            journal.admit(entry, root, listOf(command))

            assertFailsWith<IllegalArgumentException> {
                journal.admit(entry.copy(actorUserId = User.Id("other")), root, listOf(command))
            }
            assertFailsWith<IllegalArgumentException> {
                journal.admit(entry, root.copy(toolName = "changed"), listOf(command))
            }
            assertFailsWith<IllegalStateException> {
                journal.admit(entry, root, listOf(command.copy(text = "hash collision")))
            }

            val event = CommandJournal.Event(
                id = CommandJournal.Event.Id("event"),
                entryId = entry.id,
                callId = root.id,
                sourceKey = "terminal",
                kind = CommandJournal.Event.Kind.COMPLETED,
                occurredAt = at,
                exitCode = 0,
            )
            journal.append(event)
            assertFailsWith<IllegalArgumentException> {
                journal.append(event.copy(id = CommandJournal.Event.Id("replacement"), exitCode = 1))
            }
            assertNull(journal.findEntryByCommandTask(CommandTask.Id("missing")))
        }
    }

    private fun entry(at: Instant) = CommandJournal.Entry(
        id = CommandJournal.Entry.Id("entry"),
        origin = CommandJournal.Entry.Origin.CONVERSATION_TOOL_CALL,
        actorUserId = User.Id("actor"),
        projectId = Project.Id("project"),
        conversationId = Conversation.Id("conversation"),
        threadId = Conversation.Thread.Id("thread"),
        agentDefinitionId = AgentDefinition.Id("agent"),
        sourceRuntimeTaskId = ConversationRuntimeTask.Id("runtime-task"),
        sourceToolCallId = ContentItem.ToolCall.Id("root-call"),
        createdAt = at,
    )

    private fun rootCall(entry: CommandJournal.Entry, at: Instant) = CommandJournal.Call(
        id = CommandJournal.Call.Id("root-call"),
        entryId = entry.id,
        sourceToolCallId = entry.sourceToolCallId,
        kind = CommandJournal.Call.Kind.COMMAND,
        toolName = "grz_execute_command",
        target = CommandJournal.Call.Target(
            workerId = ConversationRuntimeWorkerId("worker"),
            workspaceId = Workspace.Id("workspace"),
            workspaceMountId = WorkspaceMount.Id("mount"),
            workspaceRootPath = "/workspace",
            workingDirectory = "/workspace/subdir",
        ),
        createdAt = at,
    )

    private fun content(
        entry: CommandJournal.Entry,
        call: CommandJournal.Call,
        kind: CommandJournal.Content.Kind,
        text: String,
    ) = CommandJournal.Content(
        id = CommandJournal.Content.Id(sha256(text)),
        entryId = entry.id,
        callId = call.id,
        kind = kind,
        text = text,
        createdAt = entry.createdAt,
    )

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.encodeToByteArray())
        .joinToString("") { "%02x".format(it) }

    private suspend fun database(block: suspend (PGSimpleDataSource) -> Unit) {
        Assume.assumeTrue(
            "Requires isolated PostgreSQL test configuration",
            System.getenv("GROMOZEKA_POSTGRES_RUNTIME_TEST") == "true",
        )
        fun source() = PGSimpleDataSource().apply {
            setURL(requireNotNull(System.getenv("GROMOZEKA_POSTGRES_URL")))
            user = requireNotNull(System.getenv("GROMOZEKA_POSTGRES_USER"))
            password = requireNotNull(System.getenv("GROMOZEKA_POSTGRES_PASSWORD"))
        }
        val schema = "command_journal_test_${UUID.randomUUID().toString().replace("-", "")}"
        val admin = source()
        admin.connection.use { connection ->
            connection.createStatement().use { it.execute("CREATE SCHEMA $schema") }
        }
        try {
            val scoped = source().apply { currentSchema = "$schema,public" }
            Flyway.configure()
                .dataSource(scoped)
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration/postgres")
                .load()
                .migrate()
            block(scoped)
        } finally {
            admin.connection.use { connection ->
                connection.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") }
            }
        }
    }
}
