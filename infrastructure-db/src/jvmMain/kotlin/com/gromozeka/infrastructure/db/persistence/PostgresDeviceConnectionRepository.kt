package com.gromozeka.infrastructure.db.persistence

import com.gromozeka.domain.model.DeviceConnection
import com.gromozeka.domain.model.DeviceConnectionConsumption
import com.gromozeka.domain.model.DeviceConnectionDecision
import com.gromozeka.domain.model.DeviceConnectionSessionCredential
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.UserSession
import com.gromozeka.domain.model.WorkerResource
import com.gromozeka.domain.repository.DeviceConnectionRepository
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
class PostgresDeviceConnectionRepository(
    private val dataSource: DataSource,
) : DeviceConnectionRepository {
    override suspend fun create(connection: DeviceConnection): Boolean =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { database ->
                database.prepareStatement(
                    """
                    INSERT INTO device_connections(
                        id,
                        secret_hash,
                        user_code,
                        device_label,
                        platform,
                        request_client,
                        client_label,
                        worker_id,
                        worker_kind,
                        status,
                        authorized_user_id,
                        decided_by_user_id,
                        created_at,
                        expires_at,
                        decided_at,
                        consumed_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, ?, ?, NULL, NULL)
                    ON CONFLICT DO NOTHING
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, connection.id.value)
                    statement.setString(2, connection.secretHash)
                    statement.setString(3, connection.userCode)
                    statement.setString(4, connection.deviceLabel)
                    statement.setString(5, connection.platform)
                    statement.setBoolean(6, DeviceConnection.Component.CLIENT in connection.components)
                    statement.setString(7, connection.clientLabel)
                    statement.setString(8, connection.worker?.workerId?.value)
                    statement.setString(9, connection.worker?.kind?.name)
                    statement.setString(10, connection.status.name)
                    statement.setTimestamp(11, connection.createdAt.toTimestamp())
                    statement.setTimestamp(12, connection.expiresAt.toTimestamp())
                    statement.executeUpdate() == 1
                }
            }
        }

    override suspend fun findPendingByUserCode(
        userCode: String,
        now: Instant,
    ): DeviceConnection? = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT *
                FROM device_connections
                WHERE user_code = ?
                  AND status = ?
                  AND expires_at > ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, userCode)
                statement.setString(2, DeviceConnection.Status.PENDING.name)
                statement.setTimestamp(3, now.toTimestamp())
                statement.executeQuery().use { result ->
                    if (result.next()) result.toDeviceConnection() else null
                }
            }
        }
    }

    override suspend fun approve(
        userCode: String,
        userId: User.Id,
        decidedAt: Instant,
    ): DeviceConnectionDecision? = decide(
        userCode = userCode,
        userId = userId,
        decidedAt = decidedAt,
        decision = DeviceConnection.Status.APPROVED,
    )

    override suspend fun deny(
        userCode: String,
        userId: User.Id,
        decidedAt: Instant,
    ): DeviceConnectionDecision? = decide(
        userCode = userCode,
        userId = userId,
        decidedAt = decidedAt,
        decision = DeviceConnection.Status.DENIED,
    )

    override suspend fun findBySecretHash(secretHash: String): DeviceConnection? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.findBySecretHash(secretHash, forUpdate = false)
            }
        }

    override suspend fun consume(
        secretHash: String,
        consumedAt: Instant,
        sessionCredential: DeviceConnectionSessionCredential?,
        workerCredentialHash: String?,
    ): DeviceConnectionConsumption? = withContext(Dispatchers.IO) {
        dataSource.connection.use { database ->
            database.autoCommit = false
            try {
                var connection = database.findBySecretHash(secretHash, forUpdate = true)
                    ?: return@withContext null.also { database.rollback() }
                if (
                    connection.status in setOf(
                        DeviceConnection.Status.PENDING,
                        DeviceConnection.Status.APPROVED,
                    ) && connection.expiresAt <= consumedAt
                ) {
                    database.updateConnectionStatus(
                        id = connection.id,
                        status = DeviceConnection.Status.EXPIRED,
                        decidedAt = consumedAt,
                    )
                    connection = connection.copy(
                        status = DeviceConnection.Status.EXPIRED,
                        decidedAt = consumedAt,
                    )
                }
                if (connection.status !in setOf(DeviceConnection.Status.APPROVED, DeviceConnection.Status.CONSUMED)) {
                    database.commit()
                    return@withContext DeviceConnectionConsumption(
                        connection = connection,
                        session = null,
                        worker = null,
                        newlyConsumed = false,
                    )
                }

                val ownerUserId = requireNotNull(connection.authorizedUserId) {
                    "Approved device connection has no authorized User"
                }
                require(database.isActiveUser(ownerUserId)) { "Device connection User is unavailable" }
                val requestsClient = DeviceConnection.Component.CLIENT in connection.components
                val requestsWorker = DeviceConnection.Component.WORKER in connection.components
                require(requestsClient == (sessionCredential != null)) {
                    "Client session credential does not match requested components"
                }
                require(requestsWorker == (workerCredentialHash != null)) {
                    "Worker credential does not match requested components"
                }

                val newlyConsumed = connection.status == DeviceConnection.Status.APPROVED
                val session = sessionCredential?.let { credential ->
                    if (newlyConsumed) {
                        database.insertSession(ownerUserId, connection.clientLabel, credential)
                    }
                    requireNotNull(database.findSession(credential.id, credential.tokenHash)) {
                        "Device connection Client session is unavailable"
                    }
                }
                val worker = connection.worker?.let { request ->
                    val credentialHash = requireNotNull(workerCredentialHash)
                    if (newlyConsumed) {
                        database.enrollWorker(ownerUserId, request, credentialHash, consumedAt)
                    } else {
                        require(database.workerCredentialMatches(request.workerId, credentialHash)) {
                            "Device connection Worker credential has been rotated"
                        }
                    }
                    requireNotNull(database.findWorkerForUpdate(request.workerId)) {
                        "Device connection Worker is unavailable"
                    }
                }
                if (newlyConsumed) {
                    database.markConsumed(connection.id, consumedAt)
                    connection = connection.copy(
                        status = DeviceConnection.Status.CONSUMED,
                        consumedAt = consumedAt,
                    )
                }
                database.commit()
                DeviceConnectionConsumption(
                    connection = connection,
                    session = session,
                    worker = worker,
                    newlyConsumed = newlyConsumed,
                )
            } catch (error: Throwable) {
                database.rollback()
                throw error
            }
        }
    }

    private suspend fun decide(
        userCode: String,
        userId: User.Id,
        decidedAt: Instant,
        decision: DeviceConnection.Status,
    ): DeviceConnectionDecision? = withContext(Dispatchers.IO) {
        require(decision == DeviceConnection.Status.APPROVED || decision == DeviceConnection.Status.DENIED)
        dataSource.connection.use { database ->
            database.autoCommit = false
            try {
                var connection = database.findByUserCode(userCode, forUpdate = true)
                    ?: return@withContext null.also { database.rollback() }
                if (connection.status == DeviceConnection.Status.PENDING && connection.expiresAt <= decidedAt) {
                    database.updateConnectionStatus(
                        id = connection.id,
                        status = DeviceConnection.Status.EXPIRED,
                        decidedAt = decidedAt,
                    )
                    connection = connection.copy(
                        status = DeviceConnection.Status.EXPIRED,
                        decidedAt = decidedAt,
                    )
                    database.commit()
                    return@withContext DeviceConnectionDecision(connection, changed = true)
                }
                if (connection.status != DeviceConnection.Status.PENDING) {
                    database.commit()
                    return@withContext DeviceConnectionDecision(connection, changed = false)
                }
                database.prepareStatement(
                    """
                    UPDATE device_connections
                    SET status = ?,
                        authorized_user_id = ?,
                        decided_by_user_id = ?,
                        decided_at = ?
                    WHERE id = ? AND status = ?
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, decision.name)
                    statement.setString(2, userId.value.takeIf { decision == DeviceConnection.Status.APPROVED })
                    statement.setString(3, userId.value)
                    statement.setTimestamp(4, decidedAt.toTimestamp())
                    statement.setString(5, connection.id.value)
                    statement.setString(6, DeviceConnection.Status.PENDING.name)
                    check(statement.executeUpdate() == 1) { "Device connection changed while locked" }
                }
                connection = connection.copy(
                    status = decision,
                    authorizedUserId = userId.takeIf { decision == DeviceConnection.Status.APPROVED },
                    decidedByUserId = userId,
                    decidedAt = decidedAt,
                )
                database.commit()
                DeviceConnectionDecision(connection, changed = true)
            } catch (error: Throwable) {
                database.rollback()
                throw error
            }
        }
    }

    private fun Connection.findBySecretHash(
        secretHash: String,
        forUpdate: Boolean,
    ): DeviceConnection? = prepareStatement(
        "SELECT * FROM device_connections WHERE secret_hash = ?" + if (forUpdate) " FOR UPDATE" else ""
    ).use { statement ->
        statement.setString(1, secretHash)
        statement.executeQuery().use { result ->
            if (result.next()) result.toDeviceConnection() else null
        }
    }

    private fun Connection.findByUserCode(
        userCode: String,
        forUpdate: Boolean,
    ): DeviceConnection? = prepareStatement(
        "SELECT * FROM device_connections WHERE user_code = ?" + if (forUpdate) " FOR UPDATE" else ""
    ).use { statement ->
        statement.setString(1, userCode)
        statement.executeQuery().use { result ->
            if (result.next()) result.toDeviceConnection() else null
        }
    }

    private fun Connection.updateConnectionStatus(
        id: DeviceConnection.Id,
        status: DeviceConnection.Status,
        decidedAt: Instant,
    ) {
        prepareStatement(
            "UPDATE device_connections SET status = ?, decided_at = ? WHERE id = ?"
        ).use { statement ->
            statement.setString(1, status.name)
            statement.setTimestamp(2, decidedAt.toTimestamp())
            statement.setString(3, id.value)
            check(statement.executeUpdate() == 1) { "Device connection was not updated" }
        }
    }

    private fun Connection.isActiveUser(userId: User.Id): Boolean =
        prepareStatement("SELECT status FROM users WHERE id = ?").use { statement ->
            statement.setString(1, userId.value)
            statement.executeQuery().use { result ->
                result.next() && result.getString("status") == User.Status.ACTIVE.name
            }
        }

    private fun Connection.insertSession(
        userId: User.Id,
        clientLabel: String?,
        credential: DeviceConnectionSessionCredential,
    ) {
        require(credential.tokenHash.length == 64) { "Client session token hash must contain 64 characters" }
        prepareStatement(
            """
            INSERT INTO user_sessions(
                id,
                user_id,
                token_hash,
                created_at,
                last_seen_at,
                expires_at,
                revoked_at,
                client_label
            )
            VALUES (?, ?, ?, ?, ?, ?, NULL, ?)
            ON CONFLICT (id) DO NOTHING
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, credential.id.value)
            statement.setString(2, userId.value)
            statement.setString(3, credential.tokenHash)
            statement.setTimestamp(4, credential.createdAt.toTimestamp())
            statement.setTimestamp(5, credential.createdAt.toTimestamp())
            statement.setTimestamp(6, credential.expiresAt.toTimestamp())
            statement.setString(7, clientLabel)
            statement.executeUpdate()
        }
    }

    private fun Connection.findSession(
        id: UserSession.Id,
        tokenHash: String,
    ): UserSession? = prepareStatement(
        """
        SELECT id, user_id, token_hash, created_at, last_seen_at, expires_at, revoked_at, client_label
        FROM user_sessions
        WHERE id = ? AND token_hash = ? AND revoked_at IS NULL
        """.trimIndent()
    ).use { statement ->
        statement.setString(1, id.value)
        statement.setString(2, tokenHash)
        statement.executeQuery().use { result ->
            if (!result.next()) return null
            UserSession(
                id = UserSession.Id(result.getString("id")),
                userId = User.Id(result.getString("user_id")),
                tokenHash = result.getString("token_hash"),
                createdAt = result.getTimestamp("created_at").toKotlinxInstant(),
                lastSeenAt = result.getTimestamp("last_seen_at").toKotlinxInstant(),
                expiresAt = result.getTimestamp("expires_at").toKotlinxInstant(),
                revokedAt = result.getTimestamp("revoked_at")?.toKotlinxInstant(),
                clientLabel = result.getString("client_label"),
            )
        }
    }

    private fun Connection.enrollWorker(
        ownerUserId: User.Id,
        request: DeviceConnection.WorkerRequest,
        credentialHash: String,
        enrolledAt: Instant,
    ) {
        require(credentialHash.length == 64) { "Worker gateway credential hash must contain 64 characters" }
        val existing = findWorkerForUpdate(request.workerId)
        if (existing == null) {
            insertWorker(
                WorkerResource(
                    id = request.workerId,
                    displayName = request.workerId.value,
                    ownerUserId = ownerUserId,
                    kind = request.kind,
                    subjectUserId = ownerUserId.takeIf { request.kind == WorkerResource.Kind.MOBILE_DEVICE },
                    runtimeWideAccess = false,
                    status = WorkerResource.Status.ACTIVE,
                    createdAt = enrolledAt,
                    updatedAt = enrolledAt,
                )
            )
        } else {
            require(existing.ownerUserId == ownerUserId) { "Worker ID is already registered" }
            require(existing.kind == request.kind) { "Worker ID is already registered with another kind" }
            require(existing.status == WorkerResource.Status.ACTIVE) { "Worker is revoked" }
        }
        rotateWorkerCredential(request.workerId, credentialHash, enrolledAt)
    }

    private fun Connection.findWorkerForUpdate(workerId: ConversationRuntimeWorkerId): WorkerResource? =
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
                id, display_name, owner_user_id, kind, subject_user_id,
                runtime_wide_access, status, created_at, updated_at
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
            check(statement.executeUpdate() == 1) { "Worker was not stored: ${worker.id.value}" }
        }
    }

    private fun Connection.rotateWorkerCredential(
        workerId: ConversationRuntimeWorkerId,
        credentialHash: String,
        rotatedAt: Instant,
    ) {
        prepareStatement(
            """
            INSERT INTO worker_gateway_credentials(
                worker_id, credential_hash, created_at, rotated_at, revoked_at
            )
            VALUES (?, ?, ?, ?, NULL)
            ON CONFLICT (worker_id) DO UPDATE
            SET credential_hash = EXCLUDED.credential_hash,
                rotated_at = EXCLUDED.rotated_at,
                revoked_at = NULL
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, workerId.value)
            statement.setString(2, credentialHash)
            statement.setTimestamp(3, rotatedAt.toTimestamp())
            statement.setTimestamp(4, rotatedAt.toTimestamp())
            check(statement.executeUpdate() == 1) { "Worker gateway credential was not stored" }
        }
    }

    private fun Connection.workerCredentialMatches(
        workerId: ConversationRuntimeWorkerId,
        credentialHash: String,
    ): Boolean = prepareStatement(
        """
        SELECT 1
        FROM worker_gateway_credentials
        WHERE worker_id = ? AND credential_hash = ? AND revoked_at IS NULL
        """.trimIndent()
    ).use { statement ->
        statement.setString(1, workerId.value)
        statement.setString(2, credentialHash)
        statement.executeQuery().use(ResultSet::next)
    }

    private fun Connection.markConsumed(id: DeviceConnection.Id, consumedAt: Instant) {
        prepareStatement(
            """
            UPDATE device_connections
            SET status = ?, consumed_at = ?
            WHERE id = ? AND status = ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, DeviceConnection.Status.CONSUMED.name)
            statement.setTimestamp(2, consumedAt.toTimestamp())
            statement.setString(3, id.value)
            statement.setString(4, DeviceConnection.Status.APPROVED.name)
            check(statement.executeUpdate() == 1) { "Device connection changed while locked" }
        }
    }

    private fun ResultSet.toDeviceConnection(): DeviceConnection {
        val requestsClient = getBoolean("request_client")
        val workerId = getString("worker_id")
        val components = buildSet {
            if (requestsClient) add(DeviceConnection.Component.CLIENT)
            if (workerId != null) add(DeviceConnection.Component.WORKER)
        }
        return DeviceConnection(
            id = DeviceConnection.Id(getString("id")),
            secretHash = getString("secret_hash"),
            userCode = getString("user_code"),
            deviceLabel = getString("device_label"),
            platform = getString("platform"),
            components = components,
            clientLabel = getString("client_label"),
            worker = workerId?.let {
                DeviceConnection.WorkerRequest(
                    workerId = ConversationRuntimeWorkerId(it),
                    kind = WorkerResource.Kind.valueOf(getString("worker_kind")),
                )
            },
            status = DeviceConnection.Status.valueOf(getString("status")),
            authorizedUserId = getString("authorized_user_id")?.let(User::Id),
            decidedByUserId = getString("decided_by_user_id")?.let(User::Id),
            createdAt = getTimestamp("created_at").toKotlinxInstant(),
            expiresAt = getTimestamp("expires_at").toKotlinxInstant(),
            decidedAt = getTimestamp("decided_at")?.toKotlinxInstant(),
            consumedAt = getTimestamp("consumed_at")?.toKotlinxInstant(),
        )
    }

    private fun ResultSet.toWorker(): WorkerResource = WorkerResource(
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
