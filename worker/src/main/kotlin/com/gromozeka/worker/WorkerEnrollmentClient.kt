package com.gromozeka.worker

import com.gromozeka.worker.runtime.WorkerRegistrationClient
import kotlinx.coroutines.runBlocking
import com.gromozeka.remote.protocol.WorkerEnrollmentBootstrap
import com.gromozeka.remote.protocol.WorkerEnrollmentConsumeRequest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission

internal class WorkerEnrollmentClient(
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    },
) {
    fun enroll(arguments: List<String>): Path {
        val options = WorkerEnrollmentOptions.parse(arguments)
        val bootstrap = workerRegistrationHttpClient(options.caCertificatePath).use { client ->
            runBlocking {
                WorkerRegistrationClient(client).enroll(
                    workerServerBaseUri(options.server).toString(),
                    WorkerEnrollmentConsumeRequest(
                        token = options.token,
                        workerId = options.workerId,
                        platform = System.getProperty("os.name"),
                    ),
                )
            }
        }
        return persistConfiguration(
            server = options.server,
            bootstrap = bootstrap,
            configPath = options.configPath,
            caCertificatePath = options.caCertificatePath,
            replaceExisting = options.replaceExisting,
        )
    }

    fun persistConfiguration(
        server: String,
        bootstrap: WorkerEnrollmentBootstrap,
        configPath: Path,
        caCertificatePath: Path?,
        replaceExisting: Boolean,
    ): Path {
        require(replaceExisting || !Files.exists(configPath)) {
            "Worker configuration already exists at $configPath; pass --force to replace it"
        }
        val persistedCaCertificate = caCertificatePath?.let { source ->
            persistCaCertificate(source, configPath)
        }
        writeConfiguration(
            path = configPath,
            server = workerServerBaseUri(server),
            bootstrap = bootstrap,
            caCertificatePath = persistedCaCertificate,
        )
        return configPath
    }

    fun configure(
        arguments: List<String>,
        bootstrapJson: String,
    ): Path {
        val options = WorkerBootstrapConfigurationOptions.parse(arguments)
        val bootstrap = json.decodeFromString<WorkerEnrollmentBootstrap>(bootstrapJson)
        return persistConfiguration(
            server = options.server,
            bootstrap = bootstrap,
            configPath = options.configPath,
            caCertificatePath = options.caCertificatePath,
            replaceExisting = options.replaceExisting,
        )
    }

    private fun writeConfiguration(
        path: Path,
        server: URI,
        bootstrap: WorkerEnrollmentBootstrap,
        caCertificatePath: Path?,
    ) {
        path.parent?.let(Files::createDirectories)
        Files.writeString(path, bootstrap.toYaml(server, caCertificatePath))
        runCatching {
            Files.setPosixFilePermissions(
                path,
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                ),
            )
        }
    }

    private fun persistCaCertificate(source: Path, configPath: Path): Path {
        val normalizedSource = source.toAbsolutePath().normalize()
        workerTrustManager(normalizedSource.toString())
        val trustDirectory = configPath.toAbsolutePath().normalize().parent.resolve("trust")
        Files.createDirectories(trustDirectory)
        val destination = trustDirectory.resolve("server-ca.pem")
        if (normalizedSource != destination) {
            Files.copy(normalizedSource, destination, StandardCopyOption.REPLACE_EXISTING)
        }
        return destination
    }

    private fun WorkerEnrollmentBootstrap.toYaml(
        server: URI,
        caCertificatePath: Path?,
    ): String = buildString {
        appendLine("gromozeka:")
        appendLine("  worker-gateway:")
        appendLine("    enabled: true")
        appendLine("    server-url: ${yaml(server.toString())}")
        appendLine("    credential: ${yaml(gatewayCredential)}")
        caCertificatePath?.let { path ->
            appendLine("    ca-certificate-path: ${yaml(path.toString())}")
        }
        appendLine("  runtime:")
        appendLine("    worker:")
        appendLine("      id: ${yaml(workerId)}")
        appendLine("      version: ${yaml(currentWorkerVersion())}")
        appendLine("      capabilities:")
        capabilities.sortedBy { it.name }.forEach { appendLine("        - ${yaml(it.name)}") }
    }

    private fun yaml(value: String): String = json.encodeToString(value)

}

internal fun workerServerBaseUri(server: String): URI {
    val raw = URI(server.trim().let { if ("://" in it) it else "https://$it" })
    val scheme = when (raw.scheme?.lowercase()) {
        "https", "wss" -> "https"
        "http", "ws" -> "http"
        else -> error("Server address must use HTTPS, WSS, HTTP, or WS")
    }
    val host = raw.host ?: error("Server address must include a host")
    require(scheme == "https" || host in localHosts) {
        "Remote Worker enrollment requires HTTPS"
    }
    require(raw.userInfo == null && raw.query == null && raw.fragment == null) {
        "Server address must not contain credentials, a query, or a fragment"
    }
    require(raw.path.isNullOrEmpty() || raw.path == "/") {
        "Server address must not contain a path"
    }
    return URI(
        scheme,
        null,
        host,
        raw.port,
        null,
        null,
        null,
    )
}

internal data class WorkerEnrollmentOptions(
    val server: String,
    val token: String,
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
        ): WorkerEnrollmentOptions {
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
                require(name in valueOptions) { "Unknown enrollment option: $name" }
                require(index + 1 < arguments.size) { "Missing value for $name" }
                require(values.put(name, arguments[index + 1]) == null) {
                    "Duplicate enrollment option: $name"
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
            return WorkerEnrollmentOptions(
                server = values.required("--server"),
                token = values["--token"]
                    ?.takeIf(String::isNotBlank)
                    ?: environment["GROMOZEKA_WORKER_ENROLLMENT_TOKEN"]
                        ?.takeIf(String::isNotBlank)
                    ?: error("--token or GROMOZEKA_WORKER_ENROLLMENT_TOKEN is required"),
                workerId = values.required("--worker-id"),
                configPath = values["--config"]?.let(Path::of) ?: defaultConfig,
                caCertificatePath = values["--ca-certificate"]?.let(Path::of),
                replaceExisting = replaceExisting,
            )
        }
    }
}

internal data class WorkerBootstrapConfigurationOptions(
    val server: String,
    val configPath: Path,
    val caCertificatePath: Path?,
    val replaceExisting: Boolean,
) {
    companion object {
        fun parse(
            arguments: List<String>,
            environment: Map<String, String> = System.getenv(),
            userHome: String = System.getProperty("user.home"),
        ): WorkerBootstrapConfigurationOptions {
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
                require(name in bootstrapConfigurationValueOptions) { "Unknown configuration option: $name" }
                require(index + 1 < arguments.size) { "Missing value for $name" }
                require(values.put(name, arguments[index + 1]) == null) {
                    "Duplicate configuration option: $name"
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
            return WorkerBootstrapConfigurationOptions(
                server = values.required("--server"),
                configPath = values["--config"]?.let(Path::of) ?: defaultConfig,
                caCertificatePath = values["--ca-certificate"]?.let(Path::of),
                replaceExisting = replaceExisting,
            )
        }
    }
}

private fun Map<String, String>.required(name: String): String =
    get(name)?.takeIf(String::isNotBlank)
        ?: error("$name is required")

private val valueOptions = setOf(
    "--server",
    "--token",
    "--worker-id",
    "--config",
    "--ca-certificate",
)
private val bootstrapConfigurationValueOptions = setOf(
    "--server",
    "--config",
    "--ca-certificate",
)
private val localHosts = setOf("localhost", "127.0.0.1", "::1", "0:0:0:0:0:0:0:1")
