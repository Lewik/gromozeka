package com.gromozeka.client

import com.gromozeka.remote.protocol.DistributionManifest
import com.gromozeka.remote.protocol.WorkerEnrollmentToken
import kotlinx.serialization.json.Json

class RemoteDistributionService internal constructor(
    private val client: GromozekaWsClient,
) {
    suspend fun getUpdateInventory(): com.gromozeka.remote.protocol.ApplicationUpdateInventory =
        json.decodeFromString(client.getServerResource("/api/updates/v1/installations"))

    suspend fun requestUpdate(id: String) {
        require(Regex("[a-zA-Z0-9_-]{1,100}").matches(id))
        client.postServerResource("/api/updates/v1/installations/$id/request")
    }

    suspend fun cancelUpdate(id: String) {
        require(Regex("[a-zA-Z0-9_-]{1,100}").matches(id))
        client.postServerResource("/api/updates/v1/installations/$id/cancel")
    }

    suspend fun setAutomaticUpdates(id: String, enabled: Boolean) {
        require(Regex("[a-zA-Z0-9_-]{1,100}").matches(id))
        client.postServerResource("/api/updates/v1/installations/$id/policy", "{\"automatic\":$enabled}")
    }
    private val json = Json {
        ignoreUnknownKeys = true
    }

    suspend fun getManifest(): DistributionManifest =
        json.decodeFromString(client.getServerResource("/api/distributions"))

    suspend fun createWorkerEnrollmentRequest(): WorkerEnrollmentRequest {
        val enrollment = json.decodeFromString<WorkerEnrollmentToken>(
            client.postServerResource("/api/worker-enrollments")
        )
        return WorkerEnrollmentRequest(
            serverUrl = client.serverHttpBaseUrl,
            token = enrollment.token,
            expiresAt = enrollment.expiresAt,
        )
    }

    fun workerConnectionInstructions(): WorkerConnectionInstructions =
        WorkerConnectionInstructions(
            macOsLinuxCommand = "bin/gromozeka-worker connect --server ${client.serverHttpBaseUrl} " +
                "--worker-id my-workstation",
            windowsCommand = "bin\\gromozeka-worker.cmd connect --server ${client.serverHttpBaseUrl} " +
                "--worker-id my-workstation",
        )

    suspend fun createWorkerEnrollment(): WorkerEnrollmentInstructions {
        val enrollment = createWorkerEnrollmentRequest()
        return WorkerEnrollmentInstructions(
            macOsLinuxCommand = "bin/gromozeka-worker enroll --server ${enrollment.serverUrl} " +
                "--token ${enrollment.token} --worker-id my-workstation",
            windowsCommand = "bin\\gromozeka-worker.cmd enroll --server ${enrollment.serverUrl} " +
                "--token ${enrollment.token} --worker-id my-workstation",
            expiresAt = enrollment.expiresAt,
        )
    }
}

data class WorkerEnrollmentRequest(
    val serverUrl: String,
    val token: String,
    val expiresAt: String,
)

data class WorkerEnrollmentInstructions(
    val macOsLinuxCommand: String,
    val windowsCommand: String,
    val expiresAt: String,
)

data class WorkerConnectionInstructions(
    val macOsLinuxCommand: String,
    val windowsCommand: String,
)
