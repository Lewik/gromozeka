package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.User
import com.gromozeka.domain.repository.WorkerRequestRepository
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.StoredWorkerRequest
import com.gromozeka.domain.service.PendingWorkerRequest
import com.gromozeka.domain.service.WorkerRequestProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import java.sql.ResultSet
import java.sql.Timestamp
import java.util.Base64
import javax.sql.DataSource
import kotlin.time.Instant
import kotlin.time.Clock

@Service
internal class PostgresWorkerRequestRepository(
    private val dataSource: DataSource,
    private val cipher: SecretCipher,
) : WorkerRequestRepository {
    override suspend fun create(request: StoredWorkerRequest) = withContext(Dispatchers.IO) {
        val encrypted = cipher.encrypt(Base64.getEncoder().encodeToString(request.request), "worker-request:${request.workerId.value}:${request.id}")
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement("SELECT status FROM workers WHERE id = ? FOR UPDATE").use {
                    it.setString(1, request.workerId.value)
                    it.executeQuery().use { rows -> require(rows.next() && rows.getString(1) == "ACTIVE") { "Worker is unavailable or revoked" } }
                }
                connection.prepareStatement("""
                    SELECT count(*) FROM worker_requests WHERE worker_id = ? AND completed_at IS NULL
                        AND (dispatched_at IS NOT NULL OR (start_deadline > ? AND cancel_requested_at IS NULL))
                """.trimIndent()).use {
                    it.setString(1, request.workerId.value)
                    it.setTimestamp(2, Clock.System.now().sql())
                    it.executeQuery().use { rows -> rows.next(); require(rows.getInt(1) < 256) { "Worker request queue is full" } }
                }
                connection.prepareStatement("""
                    INSERT INTO worker_requests(id, worker_id, actor_user_id, project_id, request_ciphertext, request_nonce,
                        request_version, created_at, start_deadline) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()).use {
                    it.setString(1, request.id)
                    it.setString(2, request.workerId.value)
                    it.setString(3, request.actorUserId?.value)
                    it.setString(4, request.projectId?.value)
                    it.setString(5, encrypted.ciphertext)
                    it.setString(6, encrypted.nonce)
                    it.setInt(7, encrypted.version)
                    it.setTimestamp(8, request.createdAt.sql())
                    it.setTimestamp(9, request.startDeadline.sql())
                    check(it.executeUpdate() == 1)
                }
                connection.commit()
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            }
        }
    }

    override suspend fun find(id: String): StoredWorkerRequest? = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT * FROM worker_requests WHERE id = ?").use {
                it.setString(1, id)
                it.executeQuery().use { rows -> if (rows.next()) rows.record() else null }
            }
        }
    }

    override suspend fun progress(id: String): WorkerRequestProgress? = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT dispatched_at, cancel_requested_at, completed_at FROM worker_requests WHERE id = ?").use {
                it.setString(1, id)
                it.executeQuery().use { rows ->
                    if (!rows.next()) null else WorkerRequestProgress(
                        rows.getTimestamp("dispatched_at")?.kotlin(),
                        rows.getTimestamp("cancel_requested_at")?.kotlin(),
                        rows.getTimestamp("completed_at")?.kotlin(),
                    )
                }
            }
        }
    }

    override suspend fun pending(workerId: ConversationRuntimeWorkerId, limit: Int): List<PendingWorkerRequest> = withContext(Dispatchers.IO) {
        require(limit in 1..256)
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT id, cancel_requested_at FROM worker_requests WHERE worker_id = ? AND completed_at IS NULL ORDER BY created_at, id LIMIT ?").use {
                it.setString(1, workerId.value)
                it.setInt(2, limit)
                it.executeQuery().use { rows -> buildList {
                    while (rows.next()) add(PendingWorkerRequest(rows.getString("id"), rows.getTimestamp("cancel_requested_at") != null))
                } }
            }
        }
    }

    override suspend fun markDispatched(id: String, at: Instant): Boolean = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("UPDATE worker_requests SET dispatched_at = COALESCE(dispatched_at, ?) WHERE id = ? AND completed_at IS NULL").use {
                it.setTimestamp(1, at.sql())
                it.setString(2, id)
                it.executeUpdate() == 1
            }
        }
    }

    override suspend fun cancel(id: String, at: Instant) = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("UPDATE worker_requests SET cancel_requested_at = COALESCE(cancel_requested_at, ?) WHERE id = ? AND completed_at IS NULL").use {
                it.setTimestamp(1, at.sql())
                it.setString(2, id)
                it.executeUpdate()
                Unit
            }
        }
    }

    override suspend fun complete(workerId: ConversationRuntimeWorkerId, id: String, response: ByteArray, at: Instant, onlyIfUndispatched: Boolean): Boolean = withContext(Dispatchers.IO) {
        val encrypted = cipher.encrypt(Base64.getEncoder().encodeToString(response), "worker-response:${workerId.value}:$id")
        dataSource.connection.use { connection ->
            connection.prepareStatement("""
                UPDATE worker_requests SET response_ciphertext = ?, response_nonce = ?, response_version = ?, completed_at = ?
                WHERE id = ? AND worker_id = ? AND completed_at IS NULL AND (NOT ? OR dispatched_at IS NULL)
            """.trimIndent()).use {
                it.setString(1, encrypted.ciphertext)
                it.setString(2, encrypted.nonce)
                it.setInt(3, encrypted.version)
                it.setTimestamp(4, at.sql())
                it.setString(5, id)
                it.setString(6, workerId.value)
                it.setBoolean(7, onlyIfUndispatched)
                it.executeUpdate() == 1
            }
        }
    }

    private fun ResultSet.record(): StoredWorkerRequest {
        val id = getString("id")
        val workerId = getString("worker_id")
        fun decrypt(prefix: String): ByteArray = Base64.getDecoder().decode(cipher.decrypt(
            EncryptedSecret(getString("${prefix}_ciphertext"), getString("${prefix}_nonce"), getInt("${prefix}_version")),
            "worker-$prefix:$workerId:$id",
        ))
        return StoredWorkerRequest(
            id = id,
            workerId = ConversationRuntimeWorkerId(workerId),
            request = decrypt("request"),
            actorUserId = getString("actor_user_id")?.let(User::Id),
            projectId = getString("project_id")?.let(Project::Id),
            createdAt = getTimestamp("created_at").kotlin(),
            startDeadline = getTimestamp("start_deadline").kotlin(),
            dispatchedAt = getTimestamp("dispatched_at")?.kotlin(),
            cancelRequestedAt = getTimestamp("cancel_requested_at")?.kotlin(),
            response = getString("response_ciphertext")?.let { decrypt("response") },
            completedAt = getTimestamp("completed_at")?.kotlin(),
        )
    }

    private fun Instant.sql() = Timestamp.from(java.time.Instant.ofEpochMilli(toEpochMilliseconds()))
    private fun Timestamp.kotlin() = Instant.fromEpochMilliseconds(time)
}
