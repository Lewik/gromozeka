package com.gromozeka.worker

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import com.gromozeka.worker.runtime.WorkerRegistrationClient
import com.gromozeka.domain.model.DeviceConnection
import com.gromozeka.remote.protocol.DeviceConnectionChallenge
import com.gromozeka.remote.protocol.DeviceConnectionConsumeResponse
import com.gromozeka.remote.protocol.DeviceConnectionPasswordRequest
import com.gromozeka.remote.protocol.DeviceConnectionStartRequest
import com.gromozeka.remote.protocol.DeviceConnectionWorkerRequest
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

internal class WorkerDeviceConnectionClient(
    private val enrollmentClient: WorkerEnrollmentClient = WorkerEnrollmentClient(),
) {
    fun connect(arguments: List<String>): Path = runBlocking {
        val options = WorkerDeviceConnectionOptions.parse(arguments)
        require(options.replaceExisting || !Files.exists(options.configPath)) {
            "Worker configuration already exists at ${options.configPath}; pass --force to replace it"
        }
        val base = workerServerBaseUri(options.server)
        workerRegistrationHttpClient(options.caCertificatePath).use { httpClient ->
            val client = WorkerRegistrationClient(httpClient)
            val challenge = client.start(
                serverUrl = base.toString(),
                request = DeviceConnectionStartRequest(
                    deviceLabel = options.workerId,
                    platform = System.getProperty("os.name"),
                    components = setOf(DeviceConnection.Component.WORKER),
                    worker = DeviceConnectionWorkerRequest(workerId = options.workerId),
                ),
            )
            val response = options.username?.let { username ->
                val password = requireNotNull(System.console()) {
                    "Password authentication requires an interactive terminal"
                }.readPassword("Password for %s: ", username)
                try {
                    client.authenticate(
                        serverUrl = base.toString(),
                        request = DeviceConnectionPasswordRequest(
                            deviceToken = challenge.deviceToken,
                            username = username,
                            password = password.concatToString(),
                        ),
                    )
                } finally {
                    password.fill('\u0000')
                }
            } ?: waitForApproval(client, base, challenge)
            val bootstrap = requireNotNull(response.worker) {
                "Connected Worker response has no Worker credential"
            }
            enrollmentClient.persistConfiguration(
                server = options.server,
                bootstrap = bootstrap,
                configPath = options.configPath,
                caCertificatePath = options.caCertificatePath,
                replaceExisting = options.replaceExisting,
            )
        }
    }

    private suspend fun waitForApproval(
        client: WorkerRegistrationClient,
        base: URI,
        challenge: DeviceConnectionChallenge,
    ): DeviceConnectionConsumeResponse {
        println("Open ${base}${challenge.verificationPathComplete}")
        println("Connection code: ${challenge.userCode}")
        println("Waiting for approval...")
        var delaySeconds = challenge.pollIntervalSeconds
        while (System.currentTimeMillis() < challenge.expiresAt.toEpochMilliseconds()) {
            delay(delaySeconds * 1_000L)
            val response = client.consume(base.toString(), challenge.deviceToken)
            when (response.status) {
                DeviceConnectionConsumeResponse.Status.PENDING -> {
                    delaySeconds = response.retryAfterSeconds ?: challenge.pollIntervalSeconds
                }
                DeviceConnectionConsumeResponse.Status.CONNECTED -> return response
                DeviceConnectionConsumeResponse.Status.DENIED,
                DeviceConnectionConsumeResponse.Status.EXPIRED ->
                    error(response.message ?: "Device connection ${response.status.name.lowercase()}")
            }
        }
        error("Device connection code expired")
    }

}

internal data class WorkerDeviceConnectionOptions(
    val server: String,
    val username: String?,
    val workerId: String,
    val configPath: Path,
    val caCertificatePath: Path?,
    val replaceExisting: Boolean,
) {
    companion object {
        fun parse(
            arguments: List<String>,
            environment: Map<String, String> = System.getenv(),
            userHome: String = System.getProperty("user.home"),
        ): WorkerDeviceConnectionOptions {
            val values = mutableMapOf<String, String>()
            var replaceExisting = false
            var index = 0
            while (index < arguments.size) {
                val name = arguments[index]
                if (name == "--force") {
                    replaceExisting = true
                    index += 1
                    continue
                }
                require(name in connectionValueOptions) { "Unknown connection option: $name" }
                require(index + 1 < arguments.size) { "Missing value for $name" }
                require(values.put(name, arguments[index + 1]) == null) {
                    "Duplicate connection option: $name"
                }
                index += 2
            }
            val defaultConfig = environment["GROMOZEKA_WORKER_CONFIG"]
                ?.takeIf(String::isNotBlank)
                ?.let(Path::of)
                ?: environment["GROMOZEKA_HOME"]
                    ?.takeIf(String::isNotBlank)
                    ?.let { Path.of(it, "worker.yaml") }
                ?: Path.of(userHome, ".gromozeka", "worker.yaml")
            return WorkerDeviceConnectionOptions(
                server = values.requiredConnectionOption("--server"),
                username = values["--username"]?.takeIf(String::isNotBlank),
                workerId = values.requiredConnectionOption("--worker-id"),
                configPath = values["--config"]?.let(Path::of) ?: defaultConfig,
                caCertificatePath = values["--ca-certificate"]?.let(Path::of),
                replaceExisting = replaceExisting,
            )
        }
    }
}

private fun Map<String, String>.requiredConnectionOption(name: String): String =
    get(name)?.takeIf(String::isNotBlank) ?: error("$name is required")

private val connectionValueOptions = setOf(
    "--server",
    "--username",
    "--worker-id",
    "--config",
    "--ca-certificate",
)
