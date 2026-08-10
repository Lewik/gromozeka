package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.ContextEvent
import com.gromozeka.domain.model.ContextEventAppendResult
import com.gromozeka.domain.model.ContextEventId
import com.gromozeka.domain.model.ContextStateEntry
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.projectionKey
import com.gromozeka.domain.repository.ContextStateRepository
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.springframework.stereotype.Service
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.sql.Types
import javax.sql.DataSource

@Service
class PostgresContextStateRepository(
    private val dataSource: DataSource,
) : ContextStateRepository {
    override suspend fun append(events: List<ContextEvent>): ContextEventAppendResult {
        if (events.isEmpty()) return ContextEventAppendResult(emptySet(), emptySet())
        require(events.map { it.id }.distinct().size == events.size) {
            "Context event batch contains duplicate IDs"
        }

        return withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.autoCommit = false
                try {
                    val accepted = linkedSetOf<ContextEventId>()
                    val duplicates = linkedSetOf<ContextEventId>()
                    events.forEach { event ->
                        val ingestOrder = connection.insertEvent(event)
                        if (ingestOrder != null) {
                            accepted += event.id
                            event.projectionKey()?.let { stateKey ->
                                connection.updateProjection(event, stateKey, ingestOrder)
                            }
                        } else {
                            connection.requireMatchingDuplicate(event)
                            duplicates += event.id
                        }
                    }
                    connection.commit()
                    ContextEventAppendResult(accepted, duplicates)
                } catch (error: Throwable) {
                    connection.rollback()
                    throw error
                }
            }
        }
    }

    override suspend fun currentState(
        userId: User.Id,
        subject: ContextEvent.Subject?,
    ): List<ContextStateEntry> = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            val subjectFilter = subject?.let { " AND subject_kind = ? AND subject_id = ?" }.orEmpty()
            connection.prepareStatement(
                """
                SELECT user_id, subject_kind, subject_id, state_key, event_id,
                       payload_json, observed_at, received_at
                FROM context_state_projections
                WHERE user_id = ?$subjectFilter
                ORDER BY subject_kind, subject_id, state_key
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, userId.value)
                if (subject != null) {
                    statement.setString(2, subject.kindName())
                    statement.setString(3, subject.idValue())
                }
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) add(result.toStateEntry())
                    }
                }
            }
        }
    }

    override suspend fun history(
        userId: User.Id,
        subject: ContextEvent.Subject?,
        from: Instant?,
        to: Instant?,
        limit: Int,
    ): List<ContextEvent> = withContext(Dispatchers.IO) {
        require(limit in 1..1_000) { "Context state history limit must be between 1 and 1000" }
        dataSource.connection.use { connection ->
            val predicates = mutableListOf("user_id = ?")
            if (subject != null) predicates += "subject_kind = ? AND subject_id = ?"
            if (from != null) predicates += "observed_at >= ?"
            if (to != null) predicates += "observed_at <= ?"
            connection.prepareStatement(
                """
                SELECT id, user_id, source_json, subject_kind, subject_id,
                       payload_json, observed_at, received_at
                FROM context_state_events
                WHERE ${predicates.joinToString(" AND ")}
                ORDER BY observed_at DESC, received_at DESC, ingest_order DESC
                LIMIT ?
                """.trimIndent()
            ).use { statement ->
                var index = 1
                statement.setString(index++, userId.value)
                if (subject != null) {
                    statement.setString(index++, subject.kindName())
                    statement.setString(index++, subject.idValue())
                }
                from?.let { statement.setTimestamp(index++, it.toTimestamp()) }
                to?.let { statement.setTimestamp(index++, it.toTimestamp()) }
                statement.setInt(index, limit)
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) add(result.toContextEvent())
                    }
                }
            }
        }
    }

    private fun Connection.insertEvent(event: ContextEvent): Long? =
        prepareStatement(
            """
            INSERT INTO context_state_events(
                id, user_id, source_kind, source_id, subject_kind, subject_id,
                event_type, projection_key, observed_at, received_at, source_json, payload_json
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO NOTHING
            RETURNING ingest_order
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, event.id.value)
            statement.setString(2, event.userId.value)
            statement.setString(3, event.source.kindName())
            statement.setString(4, event.source.idValue())
            statement.setString(5, event.subject.kindName())
            statement.setString(6, event.subject.idValue())
            statement.setString(7, event.payload.typeName())
            statement.setString(8, event.projectionKey())
            statement.setTimestamp(9, event.observedAt.toTimestamp())
            statement.setTimestamp(10, event.receivedAt.toTimestamp())
            statement.setJson(11, contextStateJson.encodeToString(ContextEvent.Source.serializer(), event.source))
            statement.setJson(12, contextStateJson.encodeToString(ContextEvent.Payload.serializer(), event.payload))
            statement.executeQuery().use { result ->
                if (result.next()) result.getLong("ingest_order") else null
            }
        }

    private fun Connection.requireMatchingDuplicate(event: ContextEvent) {
        prepareStatement(
            """
            SELECT user_id, source_json::text AS source_json, subject_kind, subject_id,
                   payload_json::text AS payload_json, observed_at, received_at
            FROM context_state_events
            WHERE id = ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, event.id.value)
            statement.executeQuery().use { result ->
                check(result.next()) { "Conflicting context event disappeared: ${event.id.value}" }
                val existing = ContextEvent(
                    id = event.id,
                    userId = User.Id(result.getString("user_id")),
                    source = contextStateJson.decodeFromString(
                        ContextEvent.Source.serializer(),
                        result.getString("source_json"),
                    ),
                    subject = result.toSubject(),
                    payload = contextStateJson.decodeFromString(
                        ContextEvent.Payload.serializer(),
                        result.getString("payload_json"),
                    ),
                    observedAt = result.getTimestamp("observed_at").toKotlinInstant(),
                    receivedAt = result.getTimestamp("received_at").toKotlinInstant(),
                )
                val normalizedEvent = event.copy(
                    observedAt = Instant.fromEpochMilliseconds(event.observedAt.toEpochMilliseconds()),
                    receivedAt = existing.receivedAt,
                )
                require(existing == normalizedEvent) {
                    "Context event ID ${event.id.value} was reused with different content"
                }
            }
        }
    }

    private fun Connection.updateProjection(
        event: ContextEvent,
        stateKey: String,
        ingestOrder: Long,
    ) {
        prepareStatement(
            """
            INSERT INTO context_state_projections(
                user_id, subject_kind, subject_id, state_key, event_id,
                ingest_order, observed_at, received_at, payload_json
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (user_id, subject_kind, subject_id, state_key) DO UPDATE
            SET event_id = EXCLUDED.event_id,
                ingest_order = EXCLUDED.ingest_order,
                observed_at = EXCLUDED.observed_at,
                received_at = EXCLUDED.received_at,
                payload_json = EXCLUDED.payload_json
            WHERE (LEAST(EXCLUDED.observed_at, EXCLUDED.received_at),
                   EXCLUDED.observed_at,
                   EXCLUDED.received_at,
                   EXCLUDED.ingest_order) >
                  (LEAST(context_state_projections.observed_at,
                         context_state_projections.received_at),
                   context_state_projections.observed_at,
                   context_state_projections.received_at,
                   context_state_projections.ingest_order)
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, event.userId.value)
            statement.setString(2, event.subject.kindName())
            statement.setString(3, event.subject.idValue())
            statement.setString(4, stateKey)
            statement.setString(5, event.id.value)
            statement.setLong(6, ingestOrder)
            statement.setTimestamp(7, event.observedAt.toTimestamp())
            statement.setTimestamp(8, event.receivedAt.toTimestamp())
            statement.setJson(9, contextStateJson.encodeToString(ContextEvent.Payload.serializer(), event.payload))
            statement.executeUpdate()
        }
    }

    private fun ResultSet.toStateEntry(): ContextStateEntry =
        ContextStateEntry(
            userId = User.Id(getString("user_id")),
            subject = toSubject(),
            stateKey = getString("state_key"),
            eventId = ContextEventId(getString("event_id")),
            payload = contextStateJson.decodeFromString(
                ContextEvent.Payload.serializer(),
                getString("payload_json"),
            ),
            observedAt = getTimestamp("observed_at").toKotlinInstant(),
            receivedAt = getTimestamp("received_at").toKotlinInstant(),
        )

    private fun ResultSet.toContextEvent(): ContextEvent =
        ContextEvent(
            id = ContextEventId(getString("id")),
            userId = User.Id(getString("user_id")),
            source = contextStateJson.decodeFromString(
                ContextEvent.Source.serializer(),
                getString("source_json"),
            ),
            subject = toSubject(),
            payload = contextStateJson.decodeFromString(
                ContextEvent.Payload.serializer(),
                getString("payload_json"),
            ),
            observedAt = getTimestamp("observed_at").toKotlinInstant(),
            receivedAt = getTimestamp("received_at").toKotlinInstant(),
        )

    private fun ResultSet.toSubject(): ContextEvent.Subject =
        when (getString("subject_kind")) {
            "USER" -> ContextEvent.Subject.UserState(User.Id(getString("subject_id")))
            "DEVICE" -> ContextEvent.Subject.Device(ConversationRuntimeWorkerId(getString("subject_id")))
            else -> error("Unknown context state subject kind: ${getString("subject_kind")}")
        }

    private fun PreparedStatement.setJson(index: Int, value: String) {
        setObject(index, value, Types.OTHER)
    }

    private fun ContextEvent.Source.kindName(): String =
        when (this) {
            is ContextEvent.Source.MobileWorker -> "MOBILE_WORKER"
            is ContextEvent.Source.Client -> "CLIENT"
            is ContextEvent.Source.UserDeclaration -> "USER"
            ContextEvent.Source.Server -> "SERVER"
        }

    private fun ContextEvent.Source.idValue(): String =
        when (this) {
            is ContextEvent.Source.MobileWorker -> workerId.value
            is ContextEvent.Source.Client -> instanceId
            is ContextEvent.Source.UserDeclaration -> userId.value
            ContextEvent.Source.Server -> "server"
        }

    private fun ContextEvent.Subject.kindName(): String =
        when (this) {
            is ContextEvent.Subject.UserState -> "USER"
            is ContextEvent.Subject.Device -> "DEVICE"
        }

    private fun ContextEvent.Subject.idValue(): String =
        when (this) {
            is ContextEvent.Subject.UserState -> userId.value
            is ContextEvent.Subject.Device -> workerId.value
        }

    private fun ContextEvent.Payload.typeName(): String =
        when (this) {
            is ContextEvent.Payload.Device -> "DEVICE_${event::class.simpleName.orEmpty().uppercase()}"
            is ContextEvent.Payload.ActiveClient -> "ACTIVE_CLIENT"
            is ContextEvent.Payload.UserDeclaration -> "USER_DECLARATION"
        }

    private fun Instant.toTimestamp(): Timestamp =
        Timestamp.from(java.time.Instant.ofEpochMilli(toEpochMilliseconds()))

    private fun Timestamp.toKotlinInstant(): Instant = Instant.fromEpochMilliseconds(time)
}

private val contextStateJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = false
}
