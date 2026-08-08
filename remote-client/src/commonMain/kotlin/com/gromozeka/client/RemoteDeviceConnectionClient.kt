package com.gromozeka.client

import com.gromozeka.domain.model.DeviceConnection
import com.gromozeka.remote.protocol.AuthenticationErrorResponse
import com.gromozeka.remote.protocol.DeviceConnectionChallenge
import com.gromozeka.remote.protocol.DeviceConnectionCodeRequest
import com.gromozeka.remote.protocol.DeviceConnectionConsumeRequest
import com.gromozeka.remote.protocol.DeviceConnectionConsumeResponse
import com.gromozeka.remote.protocol.DeviceConnectionPasswordRequest
import com.gromozeka.remote.protocol.DeviceConnectionPreview
import com.gromozeka.remote.protocol.DeviceConnectionStartRequest
import com.gromozeka.remote.protocol.DeviceConnectionWorkerRequest
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json

class RemoteDeviceConnectionClient private constructor(
    private val httpClient: HttpClient,
    private val baseUrl: String,
) {
    val serverHttpBaseUrl: String
        get() = baseUrl

    constructor(remoteUrl: String, httpClient: HttpClient) : this(
        httpClient = httpClient,
        baseUrl = websocketUrlToHttpBase(remoteUrl),
    )

    internal constructor(client: GromozekaWsClient) : this(
        httpClient = client.httpClient,
        baseUrl = client.serverHttpBaseUrl,
    )
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun start(
        deviceLabel: String,
        platform: String,
        components: Set<DeviceConnection.Component>,
        clientLabel: String? = null,
        worker: DeviceConnectionWorkerRequest? = null,
    ): DeviceConnectionChallenge = post(
        path = "/auth/device-connections",
        payload = DeviceConnectionStartRequest(
            deviceLabel = deviceLabel,
            platform = platform,
            components = components,
            clientLabel = clientLabel,
            worker = worker,
        ),
    )

    suspend fun preview(userCode: String): DeviceConnectionPreview =
        post("/auth/device-connections/preview", DeviceConnectionCodeRequest(userCode))

    suspend fun approve(userCode: String): DeviceConnectionPreview =
        post("/auth/device-connections/approve", DeviceConnectionCodeRequest(userCode))

    suspend fun deny(userCode: String) {
        post<_, DeviceConnectionDecisionResponse>(
            "/auth/device-connections/deny",
            DeviceConnectionCodeRequest(userCode),
        )
    }

    suspend fun consume(deviceToken: String): DeviceConnectionConsumeResponse =
        post("/auth/device-connections/consume", DeviceConnectionConsumeRequest(deviceToken))

    suspend fun connectWithPassword(
        deviceToken: String,
        username: String,
        password: String,
    ): DeviceConnectionConsumeResponse = post(
        "/auth/device-connections/password",
        DeviceConnectionPasswordRequest(
            deviceToken = deviceToken,
            username = username,
            password = password,
        ),
    )

    fun verificationUrl(challenge: DeviceConnectionChallenge): String =
        baseUrl + challenge.verificationPathComplete

    private suspend inline fun <reified TRequest, reified TResponse> post(
        path: String,
        payload: TRequest,
    ): TResponse {
        val response = httpClient.post("$baseUrl$path") {
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
        val body = response.bodyAsText()
        if (response.status.isSuccess()) {
            return json.decodeFromString(body)
        }
        val message = runCatching { json.decodeFromString<AuthenticationErrorResponse>(body).message }
            .getOrDefault(body)
            .ifBlank { "Device connection failed with HTTP ${response.status.value}" }
        error(message)
    }
}

@kotlinx.serialization.Serializable
private data class DeviceConnectionDecisionResponse(val denied: Boolean)
