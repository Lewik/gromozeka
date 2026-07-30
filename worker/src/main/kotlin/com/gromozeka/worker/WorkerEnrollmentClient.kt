package com.gromozeka.worker

import com.gromozeka.remote.protocol.WorkerEnrollmentBootstrap
import com.gromozeka.remote.protocol.WorkerEnrollmentConsumeRequest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.time.Duration

internal class WorkerEnrollmentClient(
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    },
) {
    fun enroll(arguments: List<String>): Path {
        val options = WorkerEnrollmentOptions.parse(arguments)
        val endpoint = enrollmentEndpoint(options.server)
        val body = json.encodeToString(
            WorkerEnrollmentConsumeRequest(
                token = options.token,
                workerId = options.workerId,
            )
        )
        val request = HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            val message = runCatching {
                json.parseToJsonElement(response.body())
                    .jsonObject["error"]
                    ?.jsonPrimitive
                    ?.content
            }.getOrNull()
            error(message ?: "Worker enrollment failed with HTTP ${response.statusCode()}")
        }

        val bootstrap = json.decodeFromString<WorkerEnrollmentBootstrap>(response.body())
        writeConfiguration(
            path = options.configPath,
            server = serverBaseUri(options.server),
            bootstrap = bootstrap,
            replaceExisting = options.replaceExisting,
        )
        return options.configPath
    }

    private fun writeConfiguration(
        path: Path,
        server: URI,
        bootstrap: WorkerEnrollmentBootstrap,
        replaceExisting: Boolean,
    ) {
        require(replaceExisting || !Files.exists(path)) {
            "Worker configuration already exists at $path; pass --force to replace it"
        }
        path.parent?.let(Files::createDirectories)
        Files.writeString(path, bootstrap.toYaml(server))
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

    private fun enrollmentEndpoint(server: String): URI {
        val base = serverBaseUri(server)
        return URI(
            base.scheme,
            null,
            base.host,
            base.port,
            "/api/worker-enrollments/consume",
            null,
            null,
        )
    }

    private fun serverBaseUri(server: String): URI {
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

    private fun WorkerEnrollmentBootstrap.toYaml(server: URI): String = buildString {
        appendLine("gromozeka:")
        appendLine("  worker-gateway:")
        appendLine("    enabled: true")
        appendLine("    server-url: ${yaml(server.toString())}")
        appendLine("    credential: ${yaml(gatewayCredential)}")
        appendLine("  runtime:")
        appendLine("    worker:")
        appendLine("      id: ${yaml(workerId)}")
        appendLine("      version: ${yaml(currentWorkerVersion())}")
        appendLine("      capabilities:")
        capabilities.sortedBy { it.name }.forEach { appendLine("        - ${yaml(it.name)}") }
    }

    private fun yaml(value: String): String = json.encodeToString(value)

}

internal data class WorkerEnrollmentOptions(
    val server: String,
    val token: String,
    val workerId: String,
    val configPath: Path,
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
                token = values.required("--token"),
                workerId = values.required("--worker-id"),
                configPath = values["--config"]?.let(Path::of) ?: defaultConfig,
                replaceExisting = replaceExisting,
            )
        }
    }
}

private fun Map<String, String>.required(name: String): String =
    get(name)?.takeIf(String::isNotBlank)
        ?: error("$name is required")

private val valueOptions = setOf("--server", "--token", "--worker-id", "--config")
private val localHosts = setOf("localhost", "127.0.0.1", "::1", "0:0:0:0:0:0:0:1")
