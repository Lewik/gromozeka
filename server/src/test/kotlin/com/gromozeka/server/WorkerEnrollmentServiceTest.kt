package com.gromozeka.server

import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.WorkerResource
import com.gromozeka.domain.repository.WorkerEnrollmentRepository
import com.gromozeka.domain.service.ConversationRuntimeCapability
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant as KotlinInstant
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
    fun `token can bootstrap exactly one worker`() = runBlocking {
        val repository = TestWorkerEnrollmentRepository()
        val service = WorkerEnrollmentService(configuredProperties(), repository, clock)
        val token = service.create(USER_ID)

        val bootstrap = service.consume(token.token, "macbook-primary")

        assertEquals("macbook-primary", bootstrap.workerId)
        assertTrue(bootstrap.gatewayCredential.length >= 40)
        assertEquals(setOf(ConversationRuntimeCapability.TOOL_EXECUTION), bootstrap.capabilities)
        assertEquals(USER_ID, repository.worker?.ownerUserId)
        assertFailsWith<IllegalArgumentException> {
            service.consume(token.token, "second-worker")
        }
    }

    @Test
    fun `disabled or incomplete enrollment fails closed`() = runBlocking {
        val repository = TestWorkerEnrollmentRepository()
        val disabled = WorkerEnrollmentService(WorkerEnrollmentProperties(), repository, clock)
        assertTrue(!disabled.availability().available)
        assertFailsWith<IllegalStateException> { disabled.create(USER_ID) }

        val incomplete = WorkerEnrollmentService(
            WorkerEnrollmentProperties(enabled = true, capabilities = emptySet()),
            repository,
            clock,
        )
        assertTrue(!incomplete.availability().available)
        assertFailsWith<IllegalStateException> { incomplete.create(USER_ID) }
    }

    @Test
    fun `worker id is validated before consuming token`() = runBlocking {
        val service = WorkerEnrollmentService(configuredProperties(), TestWorkerEnrollmentRepository(), clock)
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

    private companion object {
        val USER_ID = User.Id("user-1")
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
