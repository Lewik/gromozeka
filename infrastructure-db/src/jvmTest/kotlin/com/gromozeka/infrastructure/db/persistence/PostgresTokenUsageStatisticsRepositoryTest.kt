package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.TokenUsageStatistics
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.Assume.assumeTrue
import org.postgresql.ds.PGSimpleDataSource
import java.util.UUID
import kotlin.test.*
import kotlin.time.Instant

class PostgresTokenUsageStatisticsRepositoryTest {
    private val thread = Conversation.Thread.Id("main-thread")

    @Test
    fun `suggestions and reviews keep conversation context even beyond the recent-call window`() = withRepository { repository ->
        repository.save(call("main", 1, "CONVERSATION", "sol", 159_000))
        repeat(12) { index ->
            repository.save(call("aux-$index", index + 2, if (index % 2 == 0) "SUGGESTED_REPLIES" else "AGENT_RESPONSE_REVIEW", "luna", 404))
        }
        repository.save(call("foreign", 50, "CONVERSATION", "other", 900_000).copy(threadId = Conversation.Thread.Id("other-thread")))
        val totals = repository.getThreadTotals(thread)
        assertEquals(159_000, totals.currentContextSize)
        assertEquals(159_000, totals.reportedContextSize)
        assertEquals("sol", totals.modelId)
        assertEquals("OPENAI", totals.provider)
        assertEquals(TokenUsageStatistics.ContextStatus.AVAILABLE, totals.contextStatus)
        assertEquals(159_010, totals.lastCallTokens)
        assertEquals(159_000 + 12 * 404, totals.totalPromptTokens)
        assertEquals(13 * 10, totals.totalCompletionTokens)
        assertEquals(10, totals.recentCalls.size)
        assertTrue(totals.recentCalls.all { it.runtimePurpose != "CONVERSATION" })
        assertEquals(14, repository.getReport(TokenUsageStatistics.ReportQuery()).totals.callCount)

        repository.save(call("next-main", 60, "CONVERSATION", "sol", 170_000))
        repository.save(call("next-suggestions", 61, "SUGGESTED_REPLIES", "luna", 404))
        assertEquals(170_000, repository.getThreadTotals(thread).currentContextSize)
        repository.save(call("unknown-main", 70, "CONVERSATION", "sol", null))
        repository.save(call("later-suggestions", 71, "SUGGESTED_REPLIES", "luna", 404))
        val unknown = repository.getThreadTotals(thread)
        assertNull(unknown.currentContextSize)
        assertNull(unknown.reportedContextSize)
        assertEquals(TokenUsageStatistics.ContextStatus.UNAVAILABLE, unknown.contextStatus)
        assertEquals("sol", unknown.modelId)
    }

    @Test
    fun `auxiliary-only usage remains billable without manufacturing conversation context`() = withRepository { repository ->
        repository.save(call("suggestions", 1, "SUGGESTED_REPLIES", "luna", 404))
        repository.save(call("unattributed", 2, "UNSPECIFIED", "other", 500))
        val totals = repository.getThreadTotals(thread)
        assertNull(totals.currentContextSize)
        assertNull(totals.reportedContextSize)
        assertNull(totals.lastCallTokens)
        assertNull(totals.modelId)
        assertNull(totals.provider)
        assertEquals(TokenUsageStatistics.ContextStatus.UNAVAILABLE, totals.contextStatus)
        assertEquals(924, totals.totalTokens)
        assertEquals(2, totals.recentCalls.size)
    }

    private fun call(id: String, second: Int, purpose: String, model: String, context: Int?) = TokenUsageStatistics(
        id = TokenUsageStatistics.Id(id), threadId = thread, timestamp = Instant.fromEpochSeconds(second.toLong()),
        promptTokens = context ?: 100, completionTokens = 10, provider = "OPENAI", modelId = model,
        runtimePurpose = purpose, contextInputTokens = context,
    )

    private fun withRepository(block: suspend (ExposedTokenUsageStatisticsRepository) -> Unit) = runBlocking {
        assumeTrue(System.getenv("GROMOZEKA_POSTGRES_RUNTIME_TEST") == "true")
        val schema = "usage_context_${UUID.randomUUID().toString().replace("-", "")}"
        val source = PGSimpleDataSource().apply {
            setURL(System.getenv("GROMOZEKA_POSTGRES_URL") ?: "jdbc:postgresql://localhost:5432/gromozeka")
            user = System.getenv("GROMOZEKA_POSTGRES_USER") ?: "gromozeka"
            password = System.getenv("GROMOZEKA_POSTGRES_PASSWORD") ?: "gromozeka"
            currentSchema = "$schema,public"
        }
        try {
            Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration/postgres").load().migrate()
            Database.connect(source)
            block(ExposedTokenUsageStatisticsRepository())
        } finally {
            source.connection.use { it.createStatement().use { statement -> statement.execute("DROP SCHEMA IF EXISTS $schema CASCADE") } }
        }
    }
}
