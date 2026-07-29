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

    suspend fun createWorkerEnrollment(): WorkerEnrollmentInstructions {
        val enrollment = json.decodeFromString<WorkerEnrollmentToken>(
            client.postServerResource("/api/worker-enrollments")
        )
        val serverUrl = client.serverHttpBaseUrl
        return WorkerEnrollmentInstructions(
            macOsLinuxCommand = "bin/gromozeka-worker enroll --server $serverUrl " +
                "--token ${enrollment.token} --worker-id my-workstation",
            windowsCommand = "bin\\gromozeka-worker.cmd enroll --server $serverUrl " +
                "--token ${enrollment.token} --worker-id my-workstation",
            expiresAt = enrollment.expiresAt,
        )
    }
}

data class WorkerEnrollmentInstructions(
    val macOsLinuxCommand: String,
    val windowsCommand: String,
    val expiresAt: String,
)
