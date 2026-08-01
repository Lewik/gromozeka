package com.gromozeka.worker

import com.gromozeka.domain.service.ConversationRuntimeCapability
import com.gromozeka.remote.protocol.WorkerEnrollmentBootstrap
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
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
            gatewayCredential = "gateway-secret",
            capabilities = setOf(
                ConversationRuntimeCapability.TOOL_EXECUTION,
                ConversationRuntimeCapability.LOCAL_AGENT_TOOL,
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
        assertContains(config, "gateway-secret")
        assertContains(config, "http://127.0.0.1:${server.address.port}")
        assertContains(config, "enabled: true")
        assertFalse(config.contains("postgres", ignoreCase = true))
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
    fun `enrollment stores a custom server CA beside the Worker configuration`() {
        val bootstrap = WorkerEnrollmentBootstrap(
            workerId = "test-worker",
            gatewayCredential = "gateway-secret",
            capabilities = setOf(ConversationRuntimeCapability.AI_REQUEST_RESPONSE),
        )
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/api/worker-enrollments/consume") { exchange ->
                val response = Json.encodeToString(bootstrap).encodeToByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            start()
        }
        val configPath = Files.createTempDirectory("gromozeka-worker-ca-enrollment")
            .resolve("worker.yaml")
        val sourceCa = Path.of(requireNotNull(javaClass.getResource("/gromozeka-test-ca.pem")).toURI())

        try {
            WorkerEnrollmentClient().enroll(
                listOf(
                    "--server", "http://127.0.0.1:${server.address.port}",
                    "--token", "test-enrollment-token-that-is-long-enough-for-validation",
                    "--worker-id", "test-worker",
                    "--config", configPath.toString(),
                    "--ca-certificate", sourceCa.toString(),
                )
            )
        } finally {
            server.stop(0)
        }

        val persistedCa = configPath.parent.resolve("trust/server-ca.pem").toAbsolutePath()
        val config = Files.readString(configPath)
        assertTrue(Files.isRegularFile(persistedCa))
        assertContains(config, persistedCa.toString())
        workerTrustManager(persistedCa.toString())
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
        assertEquals(null, options.caCertificatePath)
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

    @Test
    fun `explicit CA certificate is parsed`() {
        val caPath = Path.of("/tmp/gromozeka-server-ca.pem")

        val options = WorkerEnrollmentOptions.parse(
            arguments = listOf(
                "--server", "https://gromozeka.example",
                "--token", "test-enrollment-token",
                "--worker-id", "test-worker",
                "--ca-certificate", caPath.toString(),
            ),
            environment = emptyMap(),
            userHome = "/unused",
        )

        assertEquals(caPath, options.caCertificatePath)
    }
}
