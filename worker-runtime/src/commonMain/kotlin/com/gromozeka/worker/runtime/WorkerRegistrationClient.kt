package com.gromozeka.worker.runtime

import com.gromozeka.remote.protocol.DeviceConnectionChallenge
import com.gromozeka.remote.protocol.DeviceConnectionConsumeRequest
import com.gromozeka.remote.protocol.DeviceConnectionConsumeResponse
import com.gromozeka.remote.protocol.DeviceConnectionPasswordRequest
import com.gromozeka.remote.protocol.DeviceConnectionStartRequest
import com.gromozeka.remote.protocol.WorkerEnrollmentBootstrap
import com.gromozeka.remote.protocol.WorkerEnrollmentConsumeRequest
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration.Companion.seconds

class WorkerRegistrationClient(private val client: HttpClient) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun enroll(serverUrl: String, request: WorkerEnrollmentConsumeRequest): WorkerEnrollmentBootstrap =
        post(serverUrl, "/api/worker-enrollments/consume", request)

    suspend fun start(serverUrl: String, request: DeviceConnectionStartRequest): DeviceConnectionChallenge =
        post(serverUrl, "/auth/device-connections", request)

    suspend fun consume(serverUrl: String, deviceToken: String): DeviceConnectionConsumeResponse =
        post(serverUrl, "/auth/device-connections/consume", DeviceConnectionConsumeRequest(deviceToken))

    suspend fun authenticate(serverUrl: String, request: DeviceConnectionPasswordRequest): DeviceConnectionConsumeResponse =
        post(serverUrl, "/auth/device-connections/password", request)

    private suspend inline fun <reified Request, reified Response> post(
        serverUrl: String,
        path: String,
        request: Request,
    ): Response = withTimeout(30.seconds) {
        val base = Url(serverUrl)
        require(base.protocol.name == "https" || base.protocol.name == "http" && base.host in localHosts) {
            "Remote Worker registration requires HTTPS"
        }
        require(base.user == null && base.password == null && base.parameters.isEmpty() && base.fragment.isEmpty()) {
            "Worker Server address must not contain credentials, a query, or a fragment"
        }
        require(base.encodedPath.isEmpty() || base.encodedPath == "/") { "Worker Server address must not contain a path" }
        val response = client.post(serverUrl.trimEnd('/') + path) {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(request))
        }
        val text = response.bodyAsText()
        check(response.status.isSuccess()) {
            val message = runCatching {
                val fields = json.parseToJsonElement(text).jsonObject
                (fields["error"] ?: fields["message"])?.jsonPrimitive?.content
            }.getOrNull()
            message ?: "Worker registration failed with HTTP ${response.status.value}"
        }
        json.decodeFromString<Response>(text)
    }

    private companion object {
        val localHosts = setOf("localhost", "127.0.0.1", "::1", "[::1]", "0:0:0:0:0:0:0:1")
    }
}
