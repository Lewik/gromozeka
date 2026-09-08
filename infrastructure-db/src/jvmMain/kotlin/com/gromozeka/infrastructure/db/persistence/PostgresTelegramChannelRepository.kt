package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.TelegramBotState
import com.gromozeka.domain.repository.TelegramBotSession
import com.gromozeka.domain.repository.TelegramChannelRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.springframework.stereotype.Service
import java.sql.Connection
import javax.sql.DataSource

@Service
class PostgresTelegramChannelRepository(
    private val dataSource: DataSource,
    private val json: Json,
) : TelegramChannelRepository {
    override suspend fun find(connectionId: String): TelegramBotState? = withContext(Dispatchers.IO) {
        dataSource.connection.use { it.readState(connectionId) }
    }

    override suspend fun openExclusiveSession(connectionId: String): TelegramBotSession? = withContext(Dispatchers.IO) {
        val key = connectionId
        val connection = dataSource.connection
        try {
            val acquired = connection.prepareStatement("SELECT pg_try_advisory_lock(hashtextextended(?, 0))").use {
                it.setString(1, "telegram:$key")
                it.executeQuery().use { result -> result.next(); result.getBoolean(1) }
            }
            if (!acquired) {
                connection.close()
                null
            } else {
                Session(connection, key)
            }
        } catch (error: Throwable) {
            connection.close()
            throw error
        }
    }

    private fun Connection.readState(key: String): TelegramBotState? =
        prepareStatement("SELECT state::text FROM telegram_bot_state WHERE connection_id = ?").use {
            it.setString(1, key)
            it.executeQuery().use { result ->
                if (result.next()) json.decodeFromString<TelegramBotState>(result.getString(1)) else null
            }
        }

    private inner class Session(private val connection: Connection, private val key: String) : TelegramBotSession {
        override suspend fun verifyLease(): Unit = withContext(Dispatchers.IO) {
            check(connection.isValid(2)) { "Telegram bot database lease was lost" }
        }

        override suspend fun load(): TelegramBotState = withContext(Dispatchers.IO) {
            connection.readState(key) ?: TelegramBotState()
        }

        override suspend fun save(state: TelegramBotState): Unit = withContext(Dispatchers.IO) {
            connection.prepareStatement("""
                INSERT INTO telegram_bot_state(connection_id, state) VALUES (?, ?::jsonb)
                ON CONFLICT (connection_id) DO UPDATE SET state = EXCLUDED.state, updated_at = now()
            """.trimIndent()).use {
                it.setString(1, key)
                it.setString(2, json.encodeToString(state))
                check(it.executeUpdate() == 1)
            }
        }

        override suspend fun close(): Unit = withContext(Dispatchers.IO) {
            try {
                connection.prepareStatement("SELECT pg_advisory_unlock(hashtextextended(?, 0))").use {
                    it.setString(1, "telegram:$key")
                    it.execute()
                }
            } finally {
                connection.close()
            }
        }
    }
}
