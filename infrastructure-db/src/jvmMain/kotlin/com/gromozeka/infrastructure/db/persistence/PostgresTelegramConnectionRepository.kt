package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.ExternalConversationChannel
import com.gromozeka.domain.model.TelegramConnection
import com.gromozeka.domain.repository.TelegramConnectionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.springframework.stereotype.Service
import javax.sql.DataSource

@Service
class PostgresTelegramConnectionRepository(private val dataSource: DataSource) : TelegramConnectionRepository {
    override suspend fun list(): List<TelegramConnection> = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT payload::text FROM telegram_connections ORDER BY id").use { statement ->
                statement.executeQuery().use { rows -> buildList {
                    while (rows.next()) add(Json.decodeFromString<TelegramConnection>(rows.getString(1)))
                } }
            }
        }
    }

    override suspend fun find(id: String): TelegramConnection? = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT payload::text FROM telegram_connections WHERE id = ?").use {
                it.setString(1, id)
                it.executeQuery().use { rows -> if (rows.next()) Json.decodeFromString(rows.getString(1)) else null }
            }
        }
    }

    override suspend fun save(connection: TelegramConnection, expectedRevision: Long): TelegramConnection = withContext(Dispatchers.IO) {
        require(expectedRevision == connection.revision)
        val saved = connection.copy(revision = expectedRevision + 1)
        dataSource.connection.use { db ->
            db.autoCommit = false
            try {
                val statement = if (expectedRevision == 0L) {
                    "INSERT INTO telegram_connections(id, revision, payload) VALUES (?, ?, ?::jsonb) ON CONFLICT DO NOTHING"
                } else {
                    "UPDATE telegram_connections SET revision = ?, payload = ?::jsonb WHERE id = ? AND revision = ?"
                }
                val changed = db.prepareStatement(statement).use {
                    if (expectedRevision == 0L) {
                        it.setString(1, saved.id); it.setLong(2, saved.revision); it.setString(3, Json.encodeToString(saved))
                    } else {
                        it.setLong(1, saved.revision); it.setString(2, Json.encodeToString(saved)); it.setString(3, saved.id); it.setLong(4, expectedRevision)
                    }
                    it.executeUpdate()
                }
                check(changed == 1) { "Telegram configuration changed; reload it before saving" }
                db.prepareStatement("UPDATE conversations SET external_channel = NULL WHERE id IN (SELECT conversation_id FROM telegram_conversation_bindings WHERE connection_id = ?)").use {
                    it.setString(1, saved.id); it.executeUpdate()
                }
                db.prepareStatement("DELETE FROM telegram_conversation_bindings WHERE connection_id = ?").use {
                    it.setString(1, saved.id); it.executeUpdate()
                }
                saved.bindings.sortedBy { it.conversationId.value }.forEach { binding ->
                    db.prepareStatement("INSERT INTO telegram_conversation_bindings(conversation_id, connection_id, chat_id, topic_id) VALUES (?, ?, ?, ?)").use {
                        it.setString(1, binding.conversationId.value); it.setString(2, saved.id)
                        it.setLong(3, binding.chatId); it.setLong(4, binding.topicId ?: 0); it.executeUpdate()
                    }
                    db.prepareStatement("UPDATE conversations SET external_channel = ?, updated_at = now() WHERE id = ?").use {
                        it.setString(1, Json.encodeToString(ExternalConversationChannel("telegram", saved.id, binding.key)))
                        it.setString(2, binding.conversationId.value)
                        check(it.executeUpdate() == 1) { "Telegram conversation does not exist" }
                    }
                }
                db.commit()
                saved
            } catch (error: Exception) {
                db.rollback()
                throw error
            }
        }
    }
}
