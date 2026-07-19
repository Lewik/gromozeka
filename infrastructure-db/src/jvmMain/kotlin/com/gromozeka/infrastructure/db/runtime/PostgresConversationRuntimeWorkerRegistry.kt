package com.gromozeka.infrastructure.db.runtime

import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import com.gromozeka.domain.service.ConversationRuntimeWorkerRegistration
import com.gromozeka.domain.service.ConversationRuntimeWorkerRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.postgresql.util.PGobject
import org.springframework.stereotype.Service
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import javax.sql.DataSource

@Service
class PostgresConversationRuntimeWorkerRegistry(
    private val dataSource: DataSource,
) : ConversationRuntimeWorkerRegistry {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    override suspend fun register(
        registration: ConversationRuntimeWorkerRegistration,
        staleBefore: Instant,
    ): Boolean =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """
                    INSERT INTO conversation_runtime_workers(
                        worker_id,
                        session_id,
                        registration_json,
                        started_at,
                        last_heartbeat_at,
                        stopped_at,
                        updated_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (worker_id) DO UPDATE
                    SET session_id = EXCLUDED.session_id,
                        registration_json = EXCLUDED.registration_json,
                        started_at = EXCLUDED.started_at,
                        last_heartbeat_at = EXCLUDED.last_heartbeat_at,
                        stopped_at = EXCLUDED.stopped_at,
                        updated_at = EXCLUDED.updated_at
                    WHERE conversation_runtime_workers.session_id = EXCLUDED.session_id
                       OR conversation_runtime_workers.stopped_at IS NOT NULL
                       OR conversation_runtime_workers.last_heartbeat_at < ?
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, registration.identity.workerId.value)
                    statement.setString(2, registration.identity.sessionId.value)
                    statement.setObject(3, jsonb(registration))
                    statement.setTimestamp(4, registration.startedAt.toTimestamp())
                    statement.setTimestamp(5, registration.lastHeartbeatAt.toTimestamp())
                    statement.setTimestamp(6, registration.stoppedAt?.toTimestamp())
                    statement.setTimestamp(7, registration.lastHeartbeatAt.toTimestamp())
                    statement.setTimestamp(8, staleBefore.toTimestamp())
                    statement.executeUpdate() == 1
                }
            }
        }

    override suspend fun heartbeat(
        identity: ConversationRuntimeWorkerIdentity,
        at: Instant,
    ): Boolean =
        mutate(identity) { registration ->
            if (registration.stoppedAt != null || at < registration.lastHeartbeatAt) {
                null
            } else {
                registration.copy(lastHeartbeatAt = at)
            }
        }

    override suspend fun unregister(
        identity: ConversationRuntimeWorkerIdentity,
        at: Instant,
    ): Boolean =
        mutate(identity) { registration ->
            if (at < registration.startedAt) {
                null
            } else {
                registration.copy(
                    lastHeartbeatAt = maxOf(registration.lastHeartbeatAt, at),
                    stoppedAt = at,
                )
            }
        }

    override suspend fun find(workerId: ConversationRuntimeWorkerId): ConversationRuntimeWorkerRegistration? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "SELECT registration_json FROM conversation_runtime_workers WHERE worker_id = ?"
                ).use { statement ->
                    statement.setString(1, workerId.value)
                    statement.executeQuery().use { result ->
                        if (result.next()) result.registration() else null
                    }
                }
            }
        }

    override suspend fun list(): List<ConversationRuntimeWorkerRegistration> =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "SELECT registration_json FROM conversation_runtime_workers ORDER BY worker_id"
                ).use { statement ->
                    statement.executeQuery().use { result ->
                        buildList {
                            while (result.next()) {
                                add(result.registration())
                            }
                        }
                    }
                }
            }
        }

    private suspend fun mutate(
        identity: ConversationRuntimeWorkerIdentity,
        transform: (ConversationRuntimeWorkerRegistration) -> ConversationRuntimeWorkerRegistration?,
    ): Boolean =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.autoCommit = false
                try {
                    val registration = connection.lock(identity.workerId)
                    if (registration == null || registration.identity != identity) {
                        connection.rollback()
                        return@withContext false
                    }
                    val updated = transform(registration)
                    if (updated == null) {
                        connection.rollback()
                        return@withContext false
                    }
                    connection.update(updated)
                    connection.commit()
                    true
                } catch (error: Throwable) {
                    connection.rollback()
                    throw error
                }
            }
        }

    private fun Connection.lock(workerId: ConversationRuntimeWorkerId): ConversationRuntimeWorkerRegistration? =
        prepareStatement(
            "SELECT registration_json FROM conversation_runtime_workers WHERE worker_id = ? FOR UPDATE"
        ).use { statement ->
            statement.setString(1, workerId.value)
            statement.executeQuery().use { result ->
                if (result.next()) result.registration() else null
            }
        }

    private fun Connection.update(registration: ConversationRuntimeWorkerRegistration) {
        prepareStatement(
            """
            UPDATE conversation_runtime_workers
            SET registration_json = ?,
                last_heartbeat_at = ?,
                stopped_at = ?,
                updated_at = ?
            WHERE worker_id = ? AND session_id = ?
            """.trimIndent()
        ).use { statement ->
            statement.setObject(1, jsonb(registration))
            statement.setTimestamp(2, registration.lastHeartbeatAt.toTimestamp())
            statement.setTimestamp(3, registration.stoppedAt?.toTimestamp())
            statement.setTimestamp(4, registration.lastHeartbeatAt.toTimestamp())
            statement.setString(5, registration.identity.workerId.value)
            statement.setString(6, registration.identity.sessionId.value)
            check(statement.executeUpdate() == 1) {
                "Conversation runtime worker registration changed while locked: ${registration.identity}"
            }
        }
    }

    private fun ResultSet.registration(): ConversationRuntimeWorkerRegistration =
        json.decodeFromString(getString("registration_json"))

    private fun jsonb(registration: ConversationRuntimeWorkerRegistration): PGobject =
        PGobject().apply {
            type = "jsonb"
            value = json.encodeToString(registration)
        }

    private fun Instant.toTimestamp(): Timestamp =
        Timestamp.from(java.time.Instant.ofEpochMilli(toEpochMilliseconds()))
}
