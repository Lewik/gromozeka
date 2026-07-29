package com.gromozeka.server

import com.gromozeka.domain.service.ConversationRuntimeCapability
import kotlinx.coroutines.runBlocking
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
        val service = WorkerEnrollmentService(configuredProperties(), clock)
        val token = service.create()

        val bootstrap = service.consume(token.token, "macbook-primary")

        assertEquals("macbook-primary", bootstrap.workerId)
        assertEquals("jdbc:postgresql://db.example/gromozeka", bootstrap.postgresJdbcUrl)
        assertEquals(setOf(ConversationRuntimeCapability.TOOL_EXECUTION), bootstrap.capabilities)
        assertFailsWith<IllegalArgumentException> {
            service.consume(token.token, "second-worker")
        }
    }

    @Test
    fun `disabled or incomplete enrollment fails closed`() = runBlocking {
        val disabled = WorkerEnrollmentService(WorkerEnrollmentProperties(), clock)
        assertTrue(!disabled.availability().available)
        assertFailsWith<IllegalStateException> { disabled.create() }

        val incomplete = WorkerEnrollmentService(
            WorkerEnrollmentProperties(enabled = true),
            clock,
        )
        assertTrue(!incomplete.availability().available)
        assertFailsWith<IllegalStateException> { incomplete.create() }
    }

    @Test
    fun `worker id is validated before consuming token`() = runBlocking {
        val service = WorkerEnrollmentService(configuredProperties(), clock)
        val token = service.create()

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
            postgresJdbcUrl = "jdbc:postgresql://db.example/gromozeka",
            postgresUsername = "worker",
            postgresPassword = "postgres-secret",
            rabbitmqAddresses = "amqps://rabbit.example:5671",
            rabbitmqUsername = "worker",
            rabbitmqPassword = "rabbit-secret",
            capabilities = setOf(ConversationRuntimeCapability.TOOL_EXECUTION),
        )
}
