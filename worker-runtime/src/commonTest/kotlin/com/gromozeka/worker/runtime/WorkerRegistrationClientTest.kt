package com.gromozeka.worker.runtime

import com.gromozeka.domain.model.DeviceConnection
import com.gromozeka.domain.model.User
import com.gromozeka.remote.protocol.DeviceConnectionStartRequest
import com.gromozeka.remote.protocol.DeviceConnectionWorkerRequest
import com.gromozeka.remote.protocol.WorkerEnrollmentConsumeRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WorkerRegistrationClientTest {
    private fun registrationTest(block: suspend CoroutineScope.() -> Unit) = runTest {
        withContext(Dispatchers.Default, block)
    }

    @Test
    fun `enrollment uses the same contract for desktop and mobile`() = registrationTest {
        for (platform in listOf("macos", "android")) {
            val client = HttpClient(MockEngine { request ->
                assertEquals("/api/worker-enrollments/consume", request.url.encodedPath)
                val body = Json.decodeFromString<WorkerEnrollmentConsumeRequest>((request.body as TextContent).text)
                assertEquals(WorkerEnrollmentConsumeRequest("token", "worker", platform, true), body)
                respond("""{"workerId":"worker","gatewayCredential":"credential","capabilities":[],"subjectUserId":"owner"}""")
            })
            try {
                val result = WorkerRegistrationClient(client).enroll(
                    "https://server.test", WorkerEnrollmentConsumeRequest("token", "worker", platform, true),
                )
                assertEquals(User.Id("owner"), result.subjectUserId)
            } finally { client.close() }
        }
    }

    @Test
    fun `device connection carries explicit user binding`() = registrationTest {
        val client = HttpClient(MockEngine { request ->
            assertEquals("/auth/device-connections", request.url.encodedPath)
            val body = Json.decodeFromString<DeviceConnectionStartRequest>((request.body as TextContent).text)
            assertEquals(true, body.worker?.bindToUser)
            assertEquals("android", body.platform)
            respond("""{"deviceToken":"token","userCode":"code","verificationPath":"/connect","verificationPathComplete":"/connect?code=code","expiresAt":"2026-09-05T12:00:00Z","pollIntervalSeconds":5}""")
        })
        try {
            val result = WorkerRegistrationClient(client).start(
                "https://server.test/",
                DeviceConnectionStartRequest(
                    "phone", "android", setOf(DeviceConnection.Component.WORKER),
                    worker = DeviceConnectionWorkerRequest("worker", bindToUser = true),
                ),
            )
            assertEquals("code", result.userCode)
        } finally { client.close() }
    }

    @Test
    fun `registration rejects insecure and ambiguous server addresses before sending secrets`() = registrationTest {
        val client = HttpClient(MockEngine { error("Must not send a request") })
        try {
            for (url in listOf("http://remote.test", "https://user:password@remote.test", "https://remote.test/path", "https://remote.test/?query=secret")) {
                assertFailsWith<IllegalArgumentException> {
                    WorkerRegistrationClient(client).enroll(url, WorkerEnrollmentConsumeRequest("secret", "worker"))
                }
            }
        } finally { client.close() }
    }

    @Test
    fun `registration surfaces server errors without parsing them as bootstrap`() = registrationTest {
        val client = HttpClient(MockEngine { respond("""{"error":"Token expired"}""", HttpStatusCode.Forbidden) })
        try {
            val failure = assertFailsWith<IllegalStateException> {
                WorkerRegistrationClient(client).enroll("https://remote.test", WorkerEnrollmentConsumeRequest("secret", "worker"))
            }
            assertEquals("Token expired", failure.message)
        } finally { client.close() }
    }
}
