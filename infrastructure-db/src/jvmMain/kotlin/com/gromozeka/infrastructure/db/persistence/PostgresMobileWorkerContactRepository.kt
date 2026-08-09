package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.MobileWorkerContactObservation
import com.gromozeka.domain.repository.MobileWorkerContactRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import org.springframework.stereotype.Service
import java.sql.Connection
import java.sql.Timestamp
import java.sql.Types
import javax.sql.DataSource

@Service
class PostgresMobileWorkerContactRepository(
    private val dataSource: DataSource,
) : MobileWorkerContactRepository {
    override suspend fun record(observation: MobileWorkerContactObservation) = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val ingestOrder = connection.insertObservation(observation)
                connection.updatePresence(observation, ingestOrder)
                connection.commit()
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            }
        }
    }

    private fun Connection.insertObservation(observation: MobileWorkerContactObservation): Long =
        prepareStatement(
            """
            INSERT INTO mobile_worker_contact_observations(
                worker_id, subject_user_id, request_id, contact_kind, app_state,
                app_version, worker_sent_at, received_at, event_count, pending_event_count
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            RETURNING ingest_order
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, observation.workerId.value)
            statement.setString(2, observation.subjectUserId.value)
            statement.setString(3, observation.requestId)
            statement.setString(4, observation.kind.name)
            statement.setString(5, observation.appState.name)
            statement.setNullableString(6, observation.appVersion)
            statement.setNullableTimestamp(7, observation.workerSentAt)
            statement.setTimestamp(8, observation.receivedAt.toTimestamp())
            statement.setInt(9, observation.eventCount)
            statement.setNullableInt(10, observation.pendingEventCount)
            statement.executeQuery().use { result ->
                check(result.next()) { "Mobile Worker contact observation was not inserted" }
                result.getLong("ingest_order")
            }
        }

    private fun Connection.updatePresence(
        observation: MobileWorkerContactObservation,
        ingestOrder: Long,
    ) {
        prepareStatement(
            """
            INSERT INTO mobile_worker_presence(
                worker_id, subject_user_id, last_observation_order, last_request_id,
                last_contact_kind, last_app_state, last_app_version, last_worker_sent_at,
                last_received_at, last_event_count, last_pending_event_count
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (worker_id) DO UPDATE
            SET subject_user_id = EXCLUDED.subject_user_id,
                last_observation_order = EXCLUDED.last_observation_order,
                last_request_id = EXCLUDED.last_request_id,
                last_contact_kind = EXCLUDED.last_contact_kind,
                last_app_state = EXCLUDED.last_app_state,
                last_app_version = EXCLUDED.last_app_version,
                last_worker_sent_at = EXCLUDED.last_worker_sent_at,
                last_received_at = EXCLUDED.last_received_at,
                last_event_count = EXCLUDED.last_event_count,
                last_pending_event_count = EXCLUDED.last_pending_event_count
            WHERE (EXCLUDED.last_received_at, EXCLUDED.last_observation_order) >
                  (mobile_worker_presence.last_received_at, mobile_worker_presence.last_observation_order)
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, observation.workerId.value)
            statement.setString(2, observation.subjectUserId.value)
            statement.setLong(3, ingestOrder)
            statement.setString(4, observation.requestId)
            statement.setString(5, observation.kind.name)
            statement.setString(6, observation.appState.name)
            statement.setNullableString(7, observation.appVersion)
            statement.setNullableTimestamp(8, observation.workerSentAt)
            statement.setTimestamp(9, observation.receivedAt.toTimestamp())
            statement.setInt(10, observation.eventCount)
            statement.setNullableInt(11, observation.pendingEventCount)
            statement.executeUpdate()
        }
    }

    private fun java.sql.PreparedStatement.setNullableString(index: Int, value: String?) {
        if (value == null) setNull(index, Types.VARCHAR) else setString(index, value)
    }

    private fun java.sql.PreparedStatement.setNullableTimestamp(index: Int, value: Instant?) {
        if (value == null) setNull(index, Types.TIMESTAMP_WITH_TIMEZONE) else setTimestamp(index, value.toTimestamp())
    }

    private fun java.sql.PreparedStatement.setNullableInt(index: Int, value: Int?) {
        if (value == null) setNull(index, Types.INTEGER) else setInt(index, value)
    }

    private fun Instant.toTimestamp(): Timestamp =
        Timestamp.from(java.time.Instant.ofEpochMilli(toEpochMilliseconds()))
}
