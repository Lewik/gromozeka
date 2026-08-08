package com.gromozeka.client

import com.gromozeka.remote.protocol.DistributionManifest
import com.gromozeka.remote.protocol.WorkerEnrollmentToken
import kotlinx.serialization.json.Json

class RemoteDistributionService internal constructor(
    private val client: GromozekaWsClient,
) {
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
