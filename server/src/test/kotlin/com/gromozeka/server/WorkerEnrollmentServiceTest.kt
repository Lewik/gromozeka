package com.gromozeka.server

import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.WorkerResource
import com.gromozeka.domain.model.SecurityAuditEvent
import com.gromozeka.domain.model.SecurityAuditRecord
import com.gromozeka.domain.repository.WorkerEnrollmentRepository
import com.gromozeka.domain.service.ConversationRuntimeCapability
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.SecurityAuditRecorder
import kotlinx.coroutines.runBlocking
import kotlin.time.Instant as KotlinInstant
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WorkerEnrollmentServiceTest {
    private val clock = Clock.fixed(Instant.parse("2026-07-28T19:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `user binding is explicit and independent of platform`() = runBlocking {
        for (platform in listOf("android", "macos")) {
            for (bindToUser in listOf(false, true)) {
                val repository = TestWorkerEnrollmentRepository()
                val service = workerEnrollmentService(configuredProperties(), repository)
                val token = service.create(USER_ID)
                val bootstrap = service.consume(token.token, "worker", platform, bindToUser)
                assertEquals(platform, repository.worker?.platform)
                assertEquals(USER_ID.takeIf { bindToUser }, bootstrap.subjectUserId)
                assertEquals(bootstrap.subjectUserId, repository.worker?.subjectUserId)
                assertEquals(configuredProperties().capabilities, bootstrap.capabilities)
            }
        }
    }

    @Test
    fun `token can bootstrap exactly one worker`() = runBlocking {
        val repository = TestWorkerEnrollmentRepository()
        val securityAuditRecorder = TestSecurityAuditRecorder()
        val service = workerEnrollmentService(configuredProperties(), repository, securityAuditRecorder)
        val token = service.create(USER_ID)

        val bootstrap = service.consume(token.token, "macbook-primary")

        assertEquals("macbook-primary", bootstrap.workerId)
        assertTrue(bootstrap.gatewayCredential.length >= 40)
        assertEquals(setOf(ConversationRuntimeCapability.TOOL_EXECUTION), bootstrap.capabilities)
        assertEquals(USER_ID, repository.worker?.ownerUserId)
        assertEquals(
            listOf(
                SecurityAuditEvent.Action.WORKER_ENROLLMENT_CREATED,
                SecurityAuditEvent.Action.WORKER_ENROLLED,
            ),
            securityAuditRecorder.records.map { it.action },
        )
        assertFailsWith<IllegalArgumentException> {
            service.consume(token.token, "second-worker")
        }
    }

    @Test
    fun `disabled or incomplete enrollment fails closed`() = runBlocking {
        val repository = TestWorkerEnrollmentRepository()
        val disabled = workerEnrollmentService(WorkerEnrollmentProperties(), repository)
        assertTrue(!disabled.availability().available)
        assertFailsWith<IllegalStateException> { disabled.create(USER_ID) }

        val incomplete = WorkerEnrollmentService(
            properties = WorkerEnrollmentProperties(enabled = true, capabilities = emptySet()),
            repository = repository,
            securityAuditRecorder = TestSecurityAuditRecorder(),
            clock = clock,
        )
        assertTrue(!incomplete.availability().available)
        assertFailsWith<IllegalStateException> { incomplete.create(USER_ID) }
    }

    @Test
    fun `worker id is validated before consuming token`() = runBlocking {
        val service = workerEnrollmentService(configuredProperties(), TestWorkerEnrollmentRepository())
        val token = service.create(USER_ID)

        assertFailsWith<IllegalArgumentException> {
            service.consume(token.token, "../unsafe")
        }
        assertEquals(
            "safe-worker",
            service.consume(token.token, "safe-worker").workerId,
        )
    }

    private fun configuredProperties(): WorkerEnrollmentProperties =
        WorkerEnrollmentProperties(
            enabled = true,
            capabilities = setOf(ConversationRuntimeCapability.TOOL_EXECUTION),
        )

    private fun workerEnrollmentService(
        properties: WorkerEnrollmentProperties,
        repository: WorkerEnrollmentRepository,
        securityAuditRecorder: TestSecurityAuditRecorder = TestSecurityAuditRecorder(),
    ): WorkerEnrollmentService =
        WorkerEnrollmentService(
            properties = properties,
            repository = repository,
            securityAuditRecorder = securityAuditRecorder,
            clock = clock,
        )

    private companion object {
        val USER_ID = User.Id("user-1")
    }
}

private class TestSecurityAuditRecorder : SecurityAuditRecorder {
    val records = mutableListOf<SecurityAuditRecord>()

    override suspend fun record(record: SecurityAuditRecord) {
        records += record
    }
}

private class TestWorkerEnrollmentRepository : WorkerEnrollmentRepository {
    private var enrollment: Enrollment? = null
    private var gatewayCredentialHash: String? = null
    var worker: WorkerResource? = null
        private set

    override suspend fun issue(
        tokenHash: String,
        ownerUserId: User.Id,
        createdAt: KotlinInstant,
        expiresAt: KotlinInstant,
    ) {
        enrollment = Enrollment(tokenHash, ownerUserId, expiresAt)
    }

    override suspend fun consume(
        tokenHash: String,
        gatewayCredentialHash: String,
        workerId: ConversationRuntimeWorkerId,
        displayName: String,
        consumedAt: KotlinInstant,
        platform: String?,
        bindToUser: Boolean,
    ): WorkerResource? {
        val issued = enrollment
            ?.takeIf { it.tokenHash == tokenHash && it.expiresAt > consumedAt }
            ?: return null
        enrollment = null
        this.gatewayCredentialHash = gatewayCredentialHash
        return WorkerResource(
            id = workerId,
            displayName = displayName,
            ownerUserId = issued.ownerUserId,
            platform = platform,
            subjectUserId = issued.ownerUserId.takeIf { bindToUser },
            runtimeWideAccess = false,
            status = WorkerResource.Status.ACTIVE,
            createdAt = consumedAt,
            updatedAt = consumedAt,
        ).also { worker = it }
    }

    override suspend fun authenticateGatewayCredential(
        gatewayCredentialHash: String,
    ): WorkerResource? =
        worker?.takeIf { this.gatewayCredentialHash == gatewayCredentialHash }

    private data class Enrollment(
        val tokenHash: String,
        val ownerUserId: User.Id,
        val expiresAt: KotlinInstant,
    )
}
