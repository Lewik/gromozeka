package com.gromozeka.client

import com.gromozeka.remote.protocol.AuthenticationErrorResponse
import com.gromozeka.remote.protocol.AuthenticationSessionResponse
import com.gromozeka.remote.protocol.AuthenticationStatusResponse
import com.gromozeka.remote.protocol.BootstrapUserRequest
import com.gromozeka.remote.protocol.LoginRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

fun createGromozekaHttpClient(
    remoteUrl: String? = null,
    sessionCredentialStore: RemoteSessionCredentialStore? = null,
): HttpClient =
    HttpClient {
        install(WebSockets)
        install(HttpCookies) {
            if (sessionCredentialStore != null) {
                storage = PersistentSessionCookiesStorage(
                    remoteUrl = requireNotNull(remoteUrl) {
                        "Remote URL is required with persistent session credentials"
                    },
                    credentialStore = sessionCredentialStore,
                )
            }
        }
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    encodeDefaults = true
                }
            )
        }
    }

class RemoteAuthenticationClient(
    remoteUrl: String,
    private val httpClient: HttpClient,
) {
    private val baseUrl = websocketUrlToHttpBase(remoteUrl)

    suspend fun status(): AuthenticationStatusResponse =
        httpClient.get("$baseUrl/auth/status").body()

    suspend fun login(
        username: String,
        password: String,
        clientLabel: String?,
    ): AuthenticationSessionResponse =
        post(
            path = "/auth/login",
            payload = LoginRequest(
                username = username,
                password = password,
                clientLabel = clientLabel,
            ),
        )

    suspend fun bootstrap(
        bootstrapToken: String,
        username: String,
        displayName: String,
        password: String,
        clientLabel: String?,
    ): AuthenticationSessionResponse =
        post(
            path = "/auth/bootstrap",
            payload = BootstrapUserRequest(
                bootstrapToken = bootstrapToken,
                username = username,
                displayName = displayName,
                password = password,
                clientLabel = clientLabel,
            ),
        )

    suspend fun logout() {
        val response = httpClient.post("$baseUrl/auth/logout")
        check(response.status.isSuccess()) {
            response.bodyAsText().ifBlank { "Logout failed with HTTP ${response.status.value}" }
        }
    }

    private suspend inline fun <reified TRequest> post(
        path: String,
        payload: TRequest,
    ): AuthenticationSessionResponse {
        val response = httpClient.post("$baseUrl$path") {
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
        if (response.status.isSuccess()) {
            return response.body()
        }
        val message = runCatching { response.body<AuthenticationErrorResponse>().message }
            .getOrElse { response.bodyAsText() }
            .ifBlank { "Authentication failed with HTTP ${response.status.value}" }
        error(message)
    }
}

internal fun websocketUrlToHttpBase(url: String): String =
    url
        .replaceFirst("wss://", "https://")
        .replaceFirst("ws://", "http://")
        .removeSuffix("/ws")
