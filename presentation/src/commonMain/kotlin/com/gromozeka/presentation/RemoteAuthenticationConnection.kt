package com.gromozeka.presentation

import com.gromozeka.client.RemoteDeviceConnectionClient
import com.gromozeka.client.RemoteAuthenticationClient
import com.gromozeka.client.RemoteSessionCredentialStore
import com.gromozeka.client.createGromozekaHttpClient
import com.gromozeka.remote.protocol.AuthenticationStatusResponse
import com.gromozeka.remote.protocol.DeviceConnectionChallenge
import com.gromozeka.remote.protocol.DeviceConnectionConsumeResponse
import com.gromozeka.remote.protocol.DeviceConnectionPreview
import com.gromozeka.remote.protocol.DeviceConnectionWorkerRequest
import com.gromozeka.domain.model.DeviceConnection
import com.gromozeka.presentation.ui.RemoteAuthenticationInput
import io.ktor.client.HttpClient

class RemoteAuthenticationConnection(
    remoteUrl: String,
    private val clientLabel: String,
    sessionCredentialStore: RemoteSessionCredentialStore? = null,
) : AutoCloseable {
    val httpClient: HttpClient = createGromozekaHttpClient(remoteUrl, sessionCredentialStore)
    private val authenticationClient = RemoteAuthenticationClient(remoteUrl, httpClient)
    private val deviceConnectionClient = RemoteDeviceConnectionClient(remoteUrl, httpClient)

    suspend fun status(): AuthenticationStatusResponse =
        authenticationClient.status()

    suspend fun authenticate(
        initialized: Boolean,
        input: RemoteAuthenticationInput,
        deviceToken: String? = null,
    ): DeviceConnectionConsumeResponse? {
        if (initialized) {
            if (deviceToken != null) {
                return authenticateDeviceConnectionWithPassword(
                    deviceToken = deviceToken,
                    username = input.username,
                    password = input.password,
                )
            } else {
                authenticationClient.login(
                    username = input.username,
                    password = input.password,
                    clientLabel = clientLabel,
                )
            }
        } else {
            authenticationClient.bootstrap(
                bootstrapToken = input.bootstrapToken,
                username = input.username,
                displayName = input.displayName,
                password = input.password,
                clientLabel = clientLabel,
            )
        }
        return null
    }

    suspend fun startDeviceConnection(
        deviceLabel: String,
        platform: String,
        worker: DeviceConnectionWorkerRequest? = null,
    ): DeviceConnectionChallenge = deviceConnectionClient.start(
        deviceLabel = deviceLabel,
        platform = platform,
        components = buildSet {
            add(DeviceConnection.Component.CLIENT)
            if (worker != null) add(DeviceConnection.Component.WORKER)
        },
        clientLabel = clientLabel,
        worker = worker,
    )

    suspend fun consumeDeviceConnection(deviceToken: String): DeviceConnectionConsumeResponse =
        deviceConnectionClient.consume(deviceToken)

    suspend fun authenticateDeviceConnectionWithPassword(
        deviceToken: String,
        username: String,
        password: String,
    ): DeviceConnectionConsumeResponse =
        deviceConnectionClient.connectWithPassword(deviceToken, username, password)

    fun deviceConnectionVerificationUrl(challenge: DeviceConnectionChallenge): String =
        deviceConnectionClient.verificationUrl(challenge)

    val serverHttpBaseUrl: String
        get() = deviceConnectionClient.serverHttpBaseUrl

    suspend fun previewDeviceConnection(userCode: String): DeviceConnectionPreview =
        deviceConnectionClient.preview(userCode)

    suspend fun approveDeviceConnection(userCode: String): DeviceConnectionPreview =
        deviceConnectionClient.approve(userCode)

    suspend fun denyDeviceConnection(userCode: String) {
        deviceConnectionClient.deny(userCode)
    }

    override fun close() {
        httpClient.close()
    }
}
