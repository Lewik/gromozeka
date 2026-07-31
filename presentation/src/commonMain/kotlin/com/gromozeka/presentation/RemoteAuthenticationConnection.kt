package com.gromozeka.presentation

import com.gromozeka.client.RemoteAuthenticationClient
import com.gromozeka.client.RemoteSessionCredentialStore
import com.gromozeka.client.createGromozekaHttpClient
import com.gromozeka.remote.protocol.AuthenticationStatusResponse
import com.gromozeka.presentation.ui.RemoteAuthenticationInput
import io.ktor.client.HttpClient

class RemoteAuthenticationConnection(
    remoteUrl: String,
    private val clientLabel: String,
    sessionCredentialStore: RemoteSessionCredentialStore? = null,
) : AutoCloseable {
    val httpClient: HttpClient = createGromozekaHttpClient(remoteUrl, sessionCredentialStore)
    private val authenticationClient = RemoteAuthenticationClient(remoteUrl, httpClient)

    suspend fun status(): AuthenticationStatusResponse =
        authenticationClient.status()

    suspend fun authenticate(
        initialized: Boolean,
        input: RemoteAuthenticationInput,
    ) {
        if (initialized) {
            authenticationClient.login(
                username = input.username,
                password = input.password,
                clientLabel = clientLabel,
            )
        } else {
            authenticationClient.bootstrap(
                bootstrapToken = input.bootstrapToken,
                username = input.username,
                displayName = input.displayName,
                password = input.password,
                clientLabel = clientLabel,
            )
        }
    }

    override fun close() {
        httpClient.close()
    }
}
