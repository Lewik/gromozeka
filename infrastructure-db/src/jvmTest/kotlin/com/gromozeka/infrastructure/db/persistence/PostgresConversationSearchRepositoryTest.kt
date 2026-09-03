package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ConversationSearchHit
import com.gromozeka.domain.model.ConversationSearchRequest
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.User
import kotlinx.coroutines.runBlocking
import org.postgresql.ds.PGSimpleDataSource
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PostgresConversationSearchRepositoryTest {
    @Test
    fun `search respects branches access pagination metadata and literal wildcards`() = runBlocking {
        if (System.getenv("GROMOZEKA_POSTGRES_RUNTIME_TEST") != "true") return@runBlocking

        val schema = "conversation_search_test_${UUID.randomUUID().toString().replace("-", "")}"
        val adminDataSource = dataSource()
        adminDataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public")
                statement.execute("CREATE SCHEMA $schema")
            }
        }

        try {
            val repositoryDataSource = dataSource("$schema,public")
            createBaselineSchema(repositoryDataSource)
            seed(repositoryDataSource)
            applySearchMigration(repositoryDataSource)
            val repository = PostgresConversationSearchRepository(repositoryDataSource)
            val readableProjects = setOf(Project.Id("project-1"))
            val participantUserId = User.Id("user-1")

            val firstPage = repository.search(
                ConversationSearchRequest(
                    query = "needle",
                    includeMetadataMatches = false,
                    limit = 1,
                ),
                readableProjects,
                participantUserId,
            )
            assertEquals(listOf("message-assistant"), firstPage.hits.mapNotNull { it.messageId?.value })
            val cursor = assertNotNull(firstPage.nextCursor)

            val secondPage = repository.search(
                ConversationSearchRequest(
                    query = "needle",
                    includeMetadataMatches = false,
                    limit = 1,
                    cursor = cursor,
                ),
                readableProjects,
                participantUserId,
            )
            assertEquals(listOf("message-current"), secondPage.hits.mapNotNull { it.messageId?.value })
            assertEquals(null, secondPage.nextCursor)

            val allBranches = repository.search(
                ConversationSearchRequest(
                    query = "needle",
                    includeMetadataMatches = false,
                    threadScope = ConversationSearchRequest.ThreadScope.ALL,
                ),
                readableProjects,
                participantUserId,
            )
            assertEquals(
                listOf("message-assistant", "message-current", "message-old"),
                allBranches.hits.mapNotNull { it.messageId?.value },
            )

            val literalWildcard = repository.search(
                ConversationSearchRequest(
                    query = "100%_done",
                    includeMetadataMatches = false,
                ),
                readableProjects,
                participantUserId,
            )
            assertEquals(listOf("message-literal"), literalWildcard.hits.mapNotNull { it.messageId?.value })

            val metadata = repository.search(
                ConversationSearchRequest(query = "Project One"),
                readableProjects,
                participantUserId,
            )
            assertTrue(metadata.hits.any { it.matchKind == ConversationSearchHit.MatchKind.PROJECT })

            val inaccessible = repository.search(
                ConversationSearchRequest(
                    query = "private needle",
                    includeMetadataMatches = false,
                ),
                readableProjects,
                participantUserId,
            )
            assertTrue(inaccessible.hits.isEmpty())
        } finally {
            adminDataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("DROP SCHEMA $schema CASCADE")
                }
            }
        }
    }

    private fun dataSource(schema: String? = null): PGSimpleDataSource =
        PGSimpleDataSource().apply {
            setURL(System.getenv("GROMOZEKA_POSTGRES_URL") ?: "jdbc:postgresql://localhost:5432/gromozeka")
            user = System.getenv("GROMOZEKA_POSTGRES_USER") ?: "gromozeka"
            password = System.getenv("GROMOZEKA_POSTGRES_PASSWORD") ?: "gromozeka"
            currentSchema = schema
        }

    private fun createBaselineSchema(dataSource: DataSource) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                        CREATE TABLE projects (
                            id TEXT PRIMARY KEY,
                            name TEXT NOT NULL,
                            description TEXT NULL,
                            created_at TIMESTAMPTZ NOT NULL,
                            last_used_at TIMESTAMPTZ NOT NULL
                        )
                    """.trimIndent()
                )
                statement.execute(
                    """
                        CREATE TABLE conversations (
                            id TEXT PRIMARY KEY,
                            project_id TEXT NOT NULL,
                            agent_definition_id TEXT NOT NULL,
                            display_name TEXT NOT NULL,
                            current_thread_id TEXT NOT NULL,
                            created_at TIMESTAMPTZ NOT NULL,
                            updated_at TIMESTAMPTZ NOT NULL
                        )
                    """.trimIndent()
                )
                statement.execute(
                    """
                        CREATE TABLE threads (
                            id TEXT PRIMARY KEY,
                            conversation_id TEXT NOT NULL,
                            created_at TIMESTAMPTZ NOT NULL,
                            updated_at TIMESTAMPTZ NOT NULL
                        )
                    """.trimIndent()
                )
                statement.execute(
                    """
                        CREATE TABLE messages (
                            id TEXT PRIMARY KEY,
                            conversation_id TEXT NOT NULL,
                            role TEXT NOT NULL,
                            created_at TIMESTAMPTZ NOT NULL,
                            message_json TEXT NOT NULL
                        )
                    """.trimIndent()
                )
                statement.execute(
                    """
                        CREATE TABLE thread_messages (
                            thread_id TEXT NOT NULL,
                            message_id TEXT NOT NULL,
                            position INTEGER NOT NULL,
                            PRIMARY KEY (thread_id, message_id)
                        )
                    """.trimIndent()
                )
                statement.execute(
                    """
                        CREATE TABLE conversation_user_participants (
                            conversation_id TEXT NOT NULL,
                            user_id TEXT NOT NULL,
                            PRIMARY KEY (conversation_id, user_id)
                        )
                    """.trimIndent()
                )
                statement.execute(
                    """
                        CREATE TABLE conversation_agent_participants (
                            conversation_id TEXT NOT NULL,
                            agent_definition_id TEXT NOT NULL,
                            PRIMARY KEY (conversation_id, agent_definition_id)
                        )
                    """.trimIndent()
                )
            }
        }
    }

    private fun seed(dataSource: DataSource) {
        dataSource.connection.use { connection ->
            connection.insertProject("project-1", "Project One")
            connection.insertProject("project-2", "Private Project")
            connection.insertConversation("conversation-1", "project-1", "thread-current", "Conversation One")
            connection.insertConversation("conversation-2", "project-2", "thread-private", "Private Conversation")
            connection.insertConversation("conversation-3", "project-1", "thread-non-participant", "Other Conversation")
            connection.insertParticipant("conversation-1", "user-1")
            connection.insertParticipant("conversation-2", "user-1")
            connection.insertParticipant("conversation-3", "user-2")
            connection.insertThread("thread-old", "conversation-1", "2026-08-19T09:00:00Z")
            connection.insertThread("thread-current", "conversation-1", "2026-08-19T13:00:00Z")
            connection.insertThread("thread-private", "conversation-2", "2026-08-19T13:00:00Z")
            connection.insertThread("thread-non-participant", "conversation-3", "2026-08-19T13:00:00Z")
            connection.insertMessage("message-old", "conversation-1", "USER", "needle old", "2026-08-19T10:00:00Z")
            connection.insertMessage("message-current", "conversation-1", "USER", "needle current", "2026-08-19T12:00:00.123800Z")
            connection.insertMessage("message-assistant", "conversation-1", "ASSISTANT", "needle assistant", "2026-08-19T12:00:00.123900Z", assistant = true)
            connection.insertMessage("message-literal", "conversation-1", "USER", "literal 100%_done", "2026-08-19T13:00:00Z")
            connection.insertMessage("message-similar", "conversation-1", "USER", "literal 100XXdone", "2026-08-19T13:01:00Z")
            connection.insertMessage("message-private", "conversation-2", "USER", "private needle", "2026-08-19T14:00:00Z")
            connection.insertMessage("message-non-participant", "conversation-3", "USER", "needle hidden", "2026-08-19T14:00:00Z")
            connection.link("thread-old", "message-old", 0)
            connection.link("thread-current", "message-current", 0)
            connection.link("thread-current", "message-assistant", 1)
            connection.link("thread-current", "message-literal", 2)
            connection.link("thread-current", "message-similar", 3)
            connection.link("thread-private", "message-private", 0)
            connection.link("thread-non-participant", "message-non-participant", 0)
        }
    }

    private fun Connection.insertProject(id: String, name: String) {
        prepareStatement(
            "INSERT INTO projects(id, name, description, created_at, last_used_at) VALUES (?, ?, NULL, ?, ?)"
        ).use { statement ->
            val timestamp = Timestamp.from(Instant.parse("2026-08-19T09:00:00Z"))
            statement.setString(1, id)
            statement.setString(2, name)
            statement.setTimestamp(3, timestamp)
            statement.setTimestamp(4, timestamp)
            statement.executeUpdate()
        }
    }

    private fun Connection.insertConversation(id: String, projectId: String, currentThreadId: String, name: String) {
        prepareStatement(
            """
                INSERT INTO conversations(
                    id, project_id, agent_definition_id, display_name, current_thread_id, created_at, updated_at
                ) VALUES (?, ?, 'agent-1', ?, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            val timestamp = Timestamp.from(Instant.parse("2026-08-19T09:00:00Z"))
            statement.setString(1, id)
            statement.setString(2, projectId)
            statement.setString(3, name)
            statement.setString(4, currentThreadId)
            statement.setTimestamp(5, timestamp)
            statement.setTimestamp(6, timestamp)
            statement.executeUpdate()
        }
    }

    private fun Connection.insertThread(id: String, conversationId: String, updatedAt: String) {
        prepareStatement(
            "INSERT INTO threads(id, conversation_id, created_at, updated_at) VALUES (?, ?, ?, ?)"
        ).use { statement ->
            val timestamp = Timestamp.from(Instant.parse(updatedAt))
            statement.setString(1, id)
            statement.setString(2, conversationId)
            statement.setTimestamp(3, timestamp)
            statement.setTimestamp(4, timestamp)
            statement.executeUpdate()
        }
    }

    private fun Connection.insertParticipant(conversationId: String, userId: String) {
        prepareStatement(
            "INSERT INTO conversation_user_participants(conversation_id, user_id) VALUES (?, ?)"
        ).use { statement ->
            statement.setString(1, conversationId)
            statement.setString(2, userId)
            statement.executeUpdate()
        }
    }

    private fun Connection.insertMessage(
        id: String,
        conversationId: String,
        role: String,
        text: String,
        createdAt: String,
        assistant: Boolean = false,
    ) {
        val content = if (assistant) {
            """{"type":"IntermediateMessage","structured":{"fullText":"$text"}}"""
        } else {
            """{"type":"Message","text":"$text"}"""
        }
        prepareStatement(
            "INSERT INTO messages(id, conversation_id, role, created_at, message_json) VALUES (?, ?, ?, ?, ?)"
        ).use { statement ->
            statement.setString(1, id)
            statement.setString(2, conversationId)
            statement.setString(3, role)
            statement.setTimestamp(4, Timestamp.from(Instant.parse(createdAt)))
            statement.setString(5, """{"content":[$content]}""")
            statement.executeUpdate()
        }
    }

    private fun Connection.link(threadId: String, messageId: String, position: Int) {
        prepareStatement(
            "INSERT INTO thread_messages(thread_id, message_id, position) VALUES (?, ?, ?)"
        ).use { statement ->
            statement.setString(1, threadId)
            statement.setString(2, messageId)
            statement.setInt(3, position)
            statement.executeUpdate()
        }
    }

    private fun applySearchMigration(dataSource: DataSource) {
        val migration = checkNotNull(
            javaClass.classLoader.getResource("db/migration/postgres/V44__conversation_message_search.sql")
        ).readText()
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                migration
                    .split(';')
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .forEach(statement::execute)
            }
        }
    }
}
