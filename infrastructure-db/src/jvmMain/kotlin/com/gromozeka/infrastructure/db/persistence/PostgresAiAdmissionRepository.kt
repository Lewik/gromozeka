package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.ai.AiAdmissionLease
import com.gromozeka.domain.repository.AiAdmissionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.springframework.stereotype.Service
import java.sql.Timestamp
import javax.sql.DataSource

@Service
internal class PostgresAiAdmissionRepository(
    private val dataSource: DataSource,
    private val json: Json,
) : AiAdmissionRepository {
    override suspend fun restoreRemoteLeases(): List<AiAdmissionLease> = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { it.executeUpdate("DELETE FROM ai_admission_leases WHERE worker_id IS NULL") }
            connection.prepareStatement("SELECT payload_json, accepted FROM ai_admission_leases ORDER BY created_at, id").use { statement ->
                statement.executeQuery().use { rows -> buildList {
                    while (rows.next()) add(json.decodeFromString<AiAdmissionLease>(rows.getString(1)).copy(accepted = rows.getBoolean(2)))
                } }
            }
        }
    }

    override suspend fun create(lease: AiAdmissionLease): Unit = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                if (lease.workerId != null) {
                    connection.prepareStatement("""
                        SELECT worker_id, dispatched_at, completed_at, cancel_requested_at
                        FROM worker_requests WHERE id = ? FOR UPDATE
                    """.trimIndent()).use { statement ->
                        statement.setString(1, lease.workerRequestId)
                        statement.executeQuery().use { rows ->
                            require(rows.next() && rows.getString("worker_id") == lease.workerId &&
                                rows.getTimestamp("dispatched_at") != null && rows.getTimestamp("completed_at") == null &&
                                rows.getTimestamp("cancel_requested_at") == null) {
                                "AI admission requires an active request owned by this Worker"
                            }
                        }
                    }
                }
                connection.prepareStatement("""
                    INSERT INTO ai_admission_leases(id, group_id, worker_id, worker_request_id, payload_json, created_at, background_session_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()).use { statement ->
                    statement.setString(1, lease.operation.id)
                    statement.setString(2, lease.groupId.value)
                    statement.setString(3, lease.workerId)
                    statement.setString(4, lease.workerRequestId)
                    statement.setString(5, json.encodeToString(lease))
                    statement.setTimestamp(6, Timestamp.from(java.time.Instant.ofEpochMilli(lease.acquiredAt.toEpochMilliseconds())))
                    statement.setString(7, lease.operation.backgroundSessionId)
                    check(statement.executeUpdate() == 1)
                }
                connection.commit()
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            }
        }
    }

    override suspend fun accept(id: String, workerId: String): Unit = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val parent = connection.prepareStatement("SELECT worker_request_id FROM ai_admission_leases WHERE id = ? AND worker_id = ? FOR UPDATE").use {
                    it.setString(1, id); it.setString(2, workerId)
                    it.executeQuery().use { rows -> require(rows.next()) { "AI admission lease is unavailable" }; rows.getString(1) }
                }
                connection.prepareStatement("SELECT completed_at, cancel_requested_at FROM worker_requests WHERE id = ? FOR UPDATE").use {
                    it.setString(1, parent)
                    it.executeQuery().use { rows -> require(rows.next() && rows.getTimestamp(1) == null && rows.getTimestamp(2) == null) {
                        "Owning Worker request has already ended or been cancelled"
                    } }
                }
                connection.prepareStatement("UPDATE ai_admission_leases SET accepted = TRUE WHERE id = ?").use {
                    it.setString(1, id); check(it.executeUpdate() == 1)
                }
                connection.commit()
            } catch (error: Throwable) { connection.rollback(); throw error }
        }
    }

    override suspend fun finish(id: String, workerId: String?): Unit = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement("SELECT worker_id FROM ai_admission_leases WHERE id = ? FOR UPDATE").use { statement ->
                    statement.setString(1, id)
                    statement.executeQuery().use { rows ->
                        if (rows.next()) require(rows.getString(1) == workerId) { "AI admission lease belongs to another executor" }
                    }
                }
                connection.prepareStatement("DELETE FROM ai_admission_leases WHERE id = ?").use {
                    it.setString(1, id)
                    it.executeUpdate()
                }
                connection.commit()
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            }
        }
    }

    override suspend fun finishWorkerRequest(workerId: String, requestId: String): Unit = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("DELETE FROM ai_admission_leases WHERE worker_id = ? AND worker_request_id = ? AND (background_session_id IS NULL OR NOT accepted)").use {
                it.setString(1, workerId)
                it.setString(2, requestId)
                it.executeUpdate()
            }
        }
    }
}
