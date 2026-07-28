package com.gromozeka.worker

import com.gromozeka.domain.service.ConversationRuntimeWorkerCapability
import com.gromozeka.remote.protocol.WorkerEnrollmentBootstrap
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkerEnrollmentClientTest {
    @Test
    fun `enrollment stores server bootstrap without exposing token`() {
        val bootstrap = WorkerEnrollmentBootstrap(
            workerId = "test-worker",
            postgresJdbcUrl = "jdbc:postgresql://db.example/gromozeka?sslmode=verify-full",
            postgresUsername = "worker",
            postgresPassword = "postgres-secret",
            rabbitmqAddresses = "amqps://rabbit.example:5671",
            rabbitmqUsername = "worker",
            rabbitmqPassword = "rabbit-secret",
            capabilities = setOf(
                ConversationRuntimeWorkerCapability.TOOL_EXECUTION,
                ConversationRuntimeWorkerCapability.LOCAL_AGENT_TOOL,
            ),
        )
        var requestBody = ""
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/api/worker-enrollments/consume") { exchange ->
                requestBody = exchange.requestBody.bufferedReader().readText()
                val response = Json.encodeToString(bootstrap).encodeToByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            start()
        }
        val configPath = Files.createTempDirectory("gromozeka-worker-enrollment")
            .resolve("worker.yaml")

        try {
            WorkerEnrollmentClient().enroll(
                listOf(
                    "--server", "http://127.0.0.1:${server.address.port}",
                    "--token", "test-enrollment-token-that-is-long-enough-for-validation",
                    "--worker-id", "test-worker",
                    "--config", configPath.toString(),
                )
            )
        } finally {
            server.stop(0)
        }

        val config = Files.readString(configPath)
        assertContains(requestBody, "test-enrollment-token")
        assertContains(requestBody, "test-worker")
        assertFalse(config.contains("test-enrollment-token"))
        assertContains(config, "postgres-secret")
        assertContains(config, "rabbit-secret")
        assertContains(config, "verify-hostname: true")
    }

    @Test
    fun `enrollment refuses plaintext remote server`() {
        val configPath = Files.createTempDirectory("gromozeka-worker-enrollment")
            .resolve("worker.yaml")

        val error = assertFailsWith<IllegalArgumentException> {
            WorkerEnrollmentClient().enroll(
                listOf(
                    "--server", "http://gromozeka.example",
                    "--token", "test-enrollment-token-that-is-long-enough-for-validation",
                    "--worker-id", "test-worker",
                    "--config", configPath.toString(),
                )
            )
        }

        assertTrue(error.message.orEmpty().contains("requires HTTPS"))
    }

    @Test
    fun `default configuration follows gromozeka home`() {
        val home = Files.createTempDirectory("gromozeka-worker-home")

        val options = WorkerEnrollmentOptions.parse(
            arguments = listOf(
                "--server", "https://gromozeka.example",
                "--token", "test-enrollment-token",
                "--worker-id", "test-worker",
            ),
            environment = mapOf("GROMOZEKA_HOME" to home.toString()),
            userHome = "/unused",
        )

        assertEquals(home.resolve("worker.yaml"), options.configPath)
    }

    @Test
    fun `explicit worker configuration overrides gromozeka home`() {
        val configPath = Files.createTempDirectory("gromozeka-worker-config")
            .resolve("custom.yaml")

        val options = WorkerEnrollmentOptions.parse(
            arguments = listOf(
                "--server", "https://gromozeka.example",
                "--token", "test-enrollment-token",
                "--worker-id", "test-worker",
            ),
            environment = mapOf(
                "GROMOZEKA_HOME" to "/unused-home",
                "GROMOZEKA_WORKER_CONFIG" to configPath.toString(),
            ),
            userHome = "/unused",
        )

        assertEquals(configPath, options.configPath)
    }
}
