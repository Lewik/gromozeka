package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.WorkerResource
import com.gromozeka.domain.repository.WorkerEnrollmentRepository
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import org.springframework.stereotype.Service
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import javax.sql.DataSource

@Service
class PostgresWorkerEnrollmentRepository(
    private val dataSource: DataSource,
) : WorkerEnrollmentRepository {
    override suspend fun issue(
        tokenHash: String,
        ownerUserId: User.Id,
        createdAt: Instant,
        expiresAt: Instant,
    ) {
        require(tokenHash.length == 64) { "Worker enrollment token hash must contain 64 characters" }
        require(expiresAt > createdAt) { "Worker enrollment token must expire after creation" }
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """
                    DELETE FROM worker_enrollment_tokens
                    WHERE consumed_at IS NOT NULL OR expires_at <= ?
                    """.trimIndent()
                ).use { statement ->
                    statement.setTimestamp(1, createdAt.toTimestamp())
                    statement.executeUpdate()
                }
                connection.prepareStatement(
                    """
                    INSERT INTO worker_enrollment_tokens(
                        token_hash,
                        owner_user_id,
                        created_at,
                        expires_at,
                        consumed_at
                    )
                    VALUES (?, ?, ?, ?, NULL)
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, tokenHash)
                    statement.setString(2, ownerUserId.value)
                    statement.setTimestamp(3, createdAt.toTimestamp())
                    statement.setTimestamp(4, expiresAt.toTimestamp())
                    check(statement.executeUpdate() == 1) {
                        "Worker enrollment token was not stored"
                    }
                }
            }
        }
    }

    override suspend fun consume(
        tokenHash: String,
        gatewayCredentialHash: String,
        workerId: ConversationRuntimeWorkerId,
        displayName: String,
        consumedAt: Instant,
        kind: WorkerResource.Kind,
    ): WorkerResource? =
        withContext(Dispatchers.IO) {
            require(gatewayCredentialHash.length == 64) {
                "Worker gateway credential hash must contain 64 characters"
            }
            dataSource.connection.use { connection ->
                connection.autoCommit = false
                try {
                    val ownerUserId = connection.lockEnrollmentOwner(tokenHash, consumedAt)
                    if (ownerUserId == null) {
                        connection.rollback()
                        return@withContext null
                    }
                    val worker = connection.findWorkerForUpdate(workerId)
                        ?.also { existing ->
                            require(existing.ownerUserId == ownerUserId) {
                                "Worker ID is already registered"
                            }
                            require(existing.kind == kind) {
                                "Worker ID is already registered with another kind"
                            }
                            require(existing.status == WorkerResource.Status.ACTIVE) {
                                "Worker is revoked"
                            }
                        }
                        ?: WorkerResource(
                            id = workerId,
                            displayName = displayName,
                            ownerUserId = ownerUserId,
                            kind = kind,
                            subjectUserId = ownerUserId.takeIf { kind == WorkerResource.Kind.MOBILE_DEVICE },
                            runtimeWideAccess = false,
                            status = WorkerResource.Status.ACTIVE,
                            createdAt = consumedAt,
                            updatedAt = consumedAt,
                        ).also { connection.insertWorker(it) }
                    connection.rotateGatewayCredential(
                        workerId = worker.id,
                        gatewayCredentialHash = gatewayCredentialHash,
                        rotatedAt = consumedAt,
                    )
                    connection.markEnrollmentConsumed(tokenHash, consumedAt)
                    connection.commit()
                    worker
                } catch (error: Throwable) {
                    connection.rollback()
                    throw error
                }
            }
        }

    override suspend fun authenticateGatewayCredential(
        gatewayCredentialHash: String,
    ): WorkerResource? =
        withContext(Dispatchers.IO) {
            if (gatewayCredentialHash.length != 64) {
                return@withContext null
            }
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """
                    SELECT w.id,
                           w.display_name,
                           w.owner_user_id,
                           w.kind,
                           w.subject_user_id,
                           w.runtime_wide_access,
                           w.status,
                           w.created_at,
                           w.updated_at
                    FROM worker_gateway_credentials c
                    JOIN workers w ON w.id = c.worker_id
                    WHERE c.credential_hash = ?
                      AND c.revoked_at IS NULL
                      AND w.status = ?
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, gatewayCredentialHash)
                    statement.setString(2, WorkerResource.Status.ACTIVE.name)
                    statement.executeQuery().use { result ->
                        if (result.next()) result.toWorker() else null
                    }
                }
            }
        }

    private fun Connection.lockEnrollmentOwner(
        tokenHash: String,
        consumedAt: Instant,
    ): User.Id? =
        prepareStatement(
            """
            SELECT owner_user_id
            FROM worker_enrollment_tokens
            WHERE token_hash = ?
              AND consumed_at IS NULL
              AND expires_at > ?
            FOR UPDATE
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, tokenHash)
            statement.setTimestamp(2, consumedAt.toTimestamp())
            statement.executeQuery().use { result ->
                if (result.next()) {
                    User.Id(result.getString("owner_user_id"))
                } else {
                    null
                }
            }
        }

    private fun Connection.findWorkerForUpdate(
        workerId: ConversationRuntimeWorkerId,
    ): WorkerResource? =
        prepareStatement(
            """
            SELECT id, display_name, owner_user_id, kind, subject_user_id,
                   runtime_wide_access, status, created_at, updated_at
            FROM workers
            WHERE id = ?
            FOR UPDATE
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, workerId.value)
            statement.executeQuery().use { result ->
                if (result.next()) result.toWorker() else null
            }
        }

    private fun Connection.insertWorker(worker: WorkerResource) {
        prepareStatement(
            """
            INSERT INTO workers(
                id,
                display_name,
                owner_user_id,
                kind,
                subject_user_id,
                runtime_wide_access,
                status,
                created_at,
                updated_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, worker.id.value)
            statement.setString(2, worker.displayName)
            statement.setString(3, worker.ownerUserId.value)
            statement.setString(4, worker.kind.name)
            statement.setString(5, worker.subjectUserId?.value)
            statement.setBoolean(6, worker.runtimeWideAccess)
            statement.setString(7, worker.status.name)
            statement.setTimestamp(8, worker.createdAt.toTimestamp())
            statement.setTimestamp(9, worker.updatedAt.toTimestamp())
            check(statement.executeUpdate() == 1) {
                "Worker was not stored: ${worker.id.value}"
            }
        }
    }

    private fun Connection.rotateGatewayCredential(
        workerId: ConversationRuntimeWorkerId,
        gatewayCredentialHash: String,
        rotatedAt: Instant,
    ) {
        prepareStatement(
            """
            INSERT INTO worker_gateway_credentials(
                worker_id,
                credential_hash,
                created_at,
                rotated_at,
                revoked_at
            )
            VALUES (?, ?, ?, ?, NULL)
            ON CONFLICT (worker_id) DO UPDATE
            SET credential_hash = EXCLUDED.credential_hash,
                rotated_at = EXCLUDED.rotated_at,
                revoked_at = NULL
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, workerId.value)
            statement.setString(2, gatewayCredentialHash)
            statement.setTimestamp(3, rotatedAt.toTimestamp())
            statement.setTimestamp(4, rotatedAt.toTimestamp())
            check(statement.executeUpdate() == 1) {
                "Worker gateway credential was not stored: ${workerId.value}"
            }
        }
    }

    private fun Connection.markEnrollmentConsumed(
        tokenHash: String,
        consumedAt: Instant,
    ) {
        prepareStatement(
            """
            UPDATE worker_enrollment_tokens
            SET consumed_at = ?
            WHERE token_hash = ? AND consumed_at IS NULL
            """.trimIndent()
        ).use { statement ->
            statement.setTimestamp(1, consumedAt.toTimestamp())
            statement.setString(2, tokenHash)
            check(statement.executeUpdate() == 1) {
                "Worker enrollment token changed while locked"
            }
        }
    }

    private fun ResultSet.toWorker(): WorkerResource =
        WorkerResource(
            id = ConversationRuntimeWorkerId(getString("id")),
            displayName = getString("display_name"),
            ownerUserId = User.Id(getString("owner_user_id")),
            kind = WorkerResource.Kind.valueOf(getString("kind")),
            subjectUserId = getString("subject_user_id")?.let(User::Id),
            runtimeWideAccess = getBoolean("runtime_wide_access"),
            status = WorkerResource.Status.valueOf(getString("status")),
            createdAt = getTimestamp("created_at").toKotlinxInstant(),
            updatedAt = getTimestamp("updated_at").toKotlinxInstant(),
        )

    private fun Instant.toTimestamp(): Timestamp =
        Timestamp.from(java.time.Instant.ofEpochMilli(toEpochMilliseconds()))

    private fun Timestamp.toKotlinxInstant(): Instant =
        Instant.fromEpochMilliseconds(time)
}
