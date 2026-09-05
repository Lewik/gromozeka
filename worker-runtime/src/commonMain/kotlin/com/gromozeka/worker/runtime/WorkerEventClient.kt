package com.gromozeka.worker.runtime

import com.gromozeka.remote.protocol.WorkerContactMetadata
import com.gromozeka.remote.protocol.WorkerEventBatchRequest
import com.gromozeka.remote.protocol.WorkerEventBatchResponse
import com.gromozeka.remote.protocol.WorkerHeartbeatRequest
import com.gromozeka.remote.protocol.WorkerHeartbeatResponse
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

class WorkerEventHttpException(val statusCode: Int) : IllegalStateException("Worker event synchronization failed with HTTP $statusCode")

class WorkerEventClient(
    client: HttpClient,
    serverUrl: String,
    private val credential: String,
) {
    private val baseUrl = serverUrl.trimEnd('/')
    init {
        val url = Url(baseUrl)
        require(url.protocol.name == "https" && url.user == null && url.password == null && url.parameters.isEmpty() && url.fragment.isEmpty()) {
            "Worker event delivery requires an HTTPS Server address without credentials, query or fragment"
        }
        require(url.encodedPath.isEmpty() || url.encodedPath == "/") { "Server address must not contain a path" }
        require(credential.isNotBlank())
    }
    private val transport = client.config { followRedirects = false; expectSuccess = false }

    suspend fun send(request: WorkerEventBatchRequest): WorkerEventBatchResponse =
        json.decodeFromString(post("events", json.encodeToString(request)))

    suspend fun heartbeat(contact: WorkerContactMetadata): WorkerHeartbeatResponse =
        json.decodeFromString(post("heartbeat", json.encodeToString(WorkerHeartbeatRequest(contact))))

    private suspend fun post(path: String, body: String): String = withTimeout(20.seconds) {
        transport.preparePost("$baseUrl/api/worker/$path") {
            header("Authorization", "Bearer $credential")
            contentType(ContentType.Application.Json)
            setBody(body)
        }.execute { response ->
            if (!response.status.isSuccess()) throw WorkerEventHttpException(response.status.value)
            val bytes = response.bodyAsChannel().readRemaining((MAX_ACKNOWLEDGEMENT_BYTES + 1).toLong()).readByteArray()
            require(bytes.size <= MAX_ACKNOWLEDGEMENT_BYTES) { "Worker event acknowledgement is too large" }
            bytes.decodeToString()
        }
    }

    fun close() { transport.close() }

    private companion object {
        const val MAX_ACKNOWLEDGEMENT_BYTES = 64 * 1024
        val json = Json { encodeDefaults = true }
    }
}
