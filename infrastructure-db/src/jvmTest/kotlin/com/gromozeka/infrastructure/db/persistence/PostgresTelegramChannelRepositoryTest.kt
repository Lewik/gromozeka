package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.TelegramBotState
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.postgresql.ds.PGSimpleDataSource
import java.util.UUID
import kotlin.test.*

class PostgresTelegramChannelRepositoryTest {
    @Test
    fun `only one poller can persist bot state and state survives reconnect`() = runBlocking {
        if (System.getenv("GROMOZEKA_POSTGRES_RUNTIME_TEST") != "true") return@runBlocking
        val schema = "telegram_test_${UUID.randomUUID().toString().replace("-", "")}"
        fun source(schemaName: String? = null) = PGSimpleDataSource().apply {
            setURL(System.getenv("GROMOZEKA_POSTGRES_URL") ?: "jdbc:postgresql://localhost:5434/gromozeka")
            user = System.getenv("GROMOZEKA_POSTGRES_USER") ?: "gromozeka"
            password = System.getenv("GROMOZEKA_POSTGRES_PASSWORD") ?: "gromozeka"
            currentSchema = schemaName
        }
        val admin = source()
        admin.connection.use { it.createStatement().use { statement -> statement.execute("CREATE SCHEMA $schema") } }
        try {
            val dataSource = source(schema)
            dataSource.connection.use { connection -> connection.createStatement().use { statement ->
                statement.execute(checkNotNull(javaClass.classLoader.getResource("db/migration/postgres/V55__telegram_channel.sql")).readText())
            } }
            val repository = PostgresTelegramChannelRepository(dataSource, Json)
            val bot = "test_${UUID.randomUUID()}"
            assertNull(repository.find(bot))
            val first = assertNotNull(repository.openExclusiveSession(bot))
            try {
                first.verifyLease()
                assertNull(repository.openExclusiveSession(bot))
                val state = TelegramBotState(nextUpdateId = 123, nextApiAttemptAt = 456)
                first.save(state)
                assertEquals(state, repository.find(bot))
            } finally { first.close() }
            val second = assertNotNull(repository.openExclusiveSession(bot))
            try { assertEquals(123, second.load().nextUpdateId) } finally { second.close() }
        } finally {
            admin.connection.use { it.createStatement().use { statement -> statement.execute("DROP SCHEMA $schema CASCADE") } }
        }
    }
}
