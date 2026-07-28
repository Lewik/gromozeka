package com.gromozeka.server

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.gromozeka.remote.protocol.WorkerEnrollmentAvailability
import com.gromozeka.remote.protocol.WorkerEnrollmentConsumeRequest

private const val DEFAULT_RELEASE_REPOSITORY = "Lewik/gromozeka"

@Serializable
internal data class DistributionManifest(
    val serverVersion: String,
    val releaseDownloadBaseUrl: String,
    val artifacts: List<DistributionArtifact>,
    val checksumsUrl: String,
    val workerEnrollment: WorkerEnrollmentAvailability,
)

@Serializable
internal data class DistributionArtifact(
    val id: String,
    val component: String,
    val operatingSystem: String,
    val architecture: String,
    val format: String,
    val fileName: String,
    val downloadPath: String,
)

internal fun Routing.gromozekaDistributions(
    workerEnrollmentService: WorkerEnrollmentService,
    serverVersion: String = currentServerVersion(),
    releaseRepository: String = configuredReleaseRepository(),
    configuredDownloadBaseUrl: String? = configuredDistributionBaseUrl(),
) {
    val releaseDownloadBaseUrl = resolveReleaseDownloadBaseUrl(
        serverVersion = serverVersion,
        releaseRepository = releaseRepository,
        configuredDownloadBaseUrl = configuredDownloadBaseUrl,
    )
    val manifest = DistributionManifest(
        serverVersion = serverVersion,
        releaseDownloadBaseUrl = releaseDownloadBaseUrl,
        artifacts = distributionArtifacts,
        checksumsUrl = "/downloads/checksums",
        workerEnrollment = workerEnrollmentService.availability(),
    )
    val manifestJson = Json {
        prettyPrint = true
        encodeDefaults = true
    }.encodeToString(manifest)

    get("/api/distributions") {
        call.respondText(manifestJson, ContentType.Application.Json)
    }
    get("/downloads") {
        call.respondText(
            distributionDownloadPage(manifest),
            ContentType.Text.Html.withCharset(Charsets.UTF_8),
        )
    }
    get("/downloads/checksums") {
        call.respondRedirect("$releaseDownloadBaseUrl/SHA256SUMS", permanent = false)
    }
    post("/api/worker-enrollments") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        if (!call.isSecureEnrollmentTransport()) {
            call.respondText(
                """{"error":"Worker enrollment requires HTTPS"}""",
                ContentType.Application.Json,
                HttpStatusCode.UpgradeRequired,
            )
            return@post
        }
        runCatching { workerEnrollmentService.create() }
            .onSuccess {
                call.respondText(
                    Json.encodeToString(it),
                    ContentType.Application.Json,
                    HttpStatusCode.Created,
                )
            }
            .onFailure {
                call.respondText(
                    Json.encodeToString(EnrollmentError(it.message ?: "Worker enrollment is unavailable")),
                    ContentType.Application.Json,
                    HttpStatusCode.ServiceUnavailable,
                )
            }
    }
    post("/api/worker-enrollments/consume") {
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        if (!call.isSecureEnrollmentTransport()) {
            call.respondText(
                """{"error":"Worker enrollment requires HTTPS"}""",
                ContentType.Application.Json,
                HttpStatusCode.UpgradeRequired,
            )
            return@post
        }
        val contentLength = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (contentLength != null && contentLength > MAX_ENROLLMENT_REQUEST_BYTES) {
            call.respondText(
                """{"error":"Worker enrollment request is too large"}""",
                ContentType.Application.Json,
                HttpStatusCode.PayloadTooLarge,
            )
            return@post
        }
        val requestText = try {
            call.receiveText()
        } catch (_: Exception) {
            call.respondText(
                """{"error":"Worker enrollment request could not be read"}""",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
            return@post
        }
        if (requestText.length > MAX_ENROLLMENT_REQUEST_BYTES) {
            call.respondText(
                """{"error":"Worker enrollment request is too large"}""",
                ContentType.Application.Json,
                HttpStatusCode.PayloadTooLarge,
            )
            return@post
        }
        runCatching {
            val request = Json.decodeFromString<WorkerEnrollmentConsumeRequest>(requestText)
            workerEnrollmentService.consume(request.token, request.workerId)
        }.onSuccess {
            call.respondText(Json.encodeToString(it), ContentType.Application.Json)
        }.onFailure {
            call.respondText(
                Json.encodeToString(EnrollmentError(it.message ?: "Worker enrollment failed")),
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
        }
    }
    distributionArtifacts.forEach { artifact ->
        get(artifact.downloadPath) {
            call.respondRedirect("$releaseDownloadBaseUrl/${artifact.fileName}", permanent = false)
        }
    }
}

internal fun resolveReleaseDownloadBaseUrl(
    serverVersion: String,
    releaseRepository: String,
    configuredDownloadBaseUrl: String?,
): String {
    configuredDownloadBaseUrl
        ?.trim()
        ?.trimEnd('/')
        ?.takeIf(String::isNotEmpty)
        ?.let { return it }

    val releaseRoot = "https://github.com/${releaseRepository.trim().trim('/')}/releases"
    return if (serverVersion != DEVELOPMENT_SERVER_VERSION && releaseVersion.matches(serverVersion)) {
        "$releaseRoot/download/v$serverVersion"
    } else {
        "$releaseRoot/latest/download"
    }
}

private fun currentServerVersion(): String =
    GromozekaServerApplication::class.java.`package`.implementationVersion
        ?.takeIf(String::isNotBlank)
        ?: DEVELOPMENT_SERVER_VERSION

private fun configuredReleaseRepository(): String =
    System.getProperty("gromozeka.distribution.release-repository")
        ?: System.getenv("GROMOZEKA_DISTRIBUTION_RELEASE_REPOSITORY")
        ?: DEFAULT_RELEASE_REPOSITORY

private fun configuredDistributionBaseUrl(): String? =
    System.getProperty("gromozeka.distribution.base-url")
        ?: System.getenv("GROMOZEKA_DISTRIBUTION_BASE_URL")

private fun distributionDownloadPage(manifest: DistributionManifest): String {
    val artifactCards = manifest.artifacts.joinToString("\n") { artifact ->
        """
        <a class="artifact" href="${artifact.downloadPath}">
          <strong>${artifact.component} for ${artifact.operatingSystem}</strong>
          <span>${artifact.architecture} · ${artifact.format}</span>
          <code>${artifact.fileName}</code>
        </a>
        """.trimIndent()
    }
    val enrollment = if (manifest.workerEnrollment.available) {
        """
        <section>
          <h2>Add a Worker</h2>
          <p>Generate a one-time token, then run the displayed command inside an extracted Worker archive.</p>
          <button id="enroll-worker" type="button">Generate enrollment token</button>
          <pre id="enrollment-command" hidden></pre>
          <p id="enrollment-error" class="error"></p>
        </section>
        <script>
          document.getElementById("enroll-worker").addEventListener("click", async () => {
            const button = document.getElementById("enroll-worker");
            const command = document.getElementById("enrollment-command");
            const error = document.getElementById("enrollment-error");
            button.disabled = true;
            error.textContent = "";
            try {
              const response = await fetch("/api/worker-enrollments", { method: "POST" });
              const payload = await response.json();
              if (!response.ok) throw new Error(payload.error || "Enrollment token generation failed");
              command.textContent =
                "macOS/Linux:\n" +
                "bin/gromozeka-worker enroll --server " + window.location.origin +
                " --token " + payload.token + " --worker-id my-workstation\n\n" +
                "Windows:\n" +
                "bin\\gromozeka-worker.cmd enroll --server " + window.location.origin +
                " --token " + payload.token + " --worker-id my-workstation\n\n" +
                "Token expires at " + payload.expiresAt;
              command.hidden = false;
            } catch (cause) {
              error.textContent = cause.message;
            } finally {
              button.disabled = false;
            }
          });
        </script>
        """.trimIndent()
    } else {
        """
        <section>
          <h2>Add a Worker</h2>
          <p>Enrollment is not configured on this Server: ${escapeHtml(manifest.workerEnrollment.unavailableReason.orEmpty())}</p>
        </section>
        """.trimIndent()
    }
    return """
        <!doctype html>
        <html lang="en">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <title>Gromozeka Downloads</title>
          <style>
            :root { color-scheme: dark; font-family: "IBM Plex Sans", "Segoe UI", sans-serif; }
            body { margin: 0; background: #111719; color: #e9f0ef; }
            main { max-width: 920px; margin: 0 auto; padding: 56px 24px 72px; }
            h1 { margin: 0 0 8px; font-size: clamp(2rem, 5vw, 3.4rem); letter-spacing: -.04em; }
            p { color: #aebcba; line-height: 1.6; }
            .version { display: inline-block; margin: 12px 0 28px; padding: 6px 10px; border: 1px solid #3c4b49; border-radius: 999px; }
            .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(260px, 1fr)); gap: 12px; }
            .artifact { display: flex; flex-direction: column; gap: 7px; padding: 18px; color: inherit; text-decoration: none;
              background: #1a2325; border: 1px solid #2e3a3c; border-radius: 12px; }
            .artifact:hover { border-color: #67c7b5; background: #1d292a; }
            .artifact span, footer { color: #91a19f; }
            code { overflow-wrap: anywhere; color: #8ad7c8; }
            section { margin-top: 32px; padding: 20px; background: #1a2325; border: 1px solid #2e3a3c; border-radius: 12px; }
            button { padding: 10px 14px; border: 0; border-radius: 8px; color: #0d1716; background: #8ad7c8; font-weight: 700; cursor: pointer; }
            button:disabled { opacity: .55; cursor: wait; }
            pre { padding: 14px; overflow: auto; background: #0d1213; border-radius: 8px; white-space: pre-wrap; overflow-wrap: anywhere; }
            .error { color: #ff9b95; }
            footer { margin-top: 32px; font-size: .9rem; }
            footer a { color: #8ad7c8; }
          </style>
        </head>
        <body>
          <main>
            <h1>Gromozeka Downloads</h1>
            <p>Native clients and trusted standalone Workers. Client archives include their Java runtime.</p>
            <div class="version">Server ${escapeHtml(manifest.serverVersion)}</div>
            <div class="grid">$artifactCards</div>
            $enrollment
            <footer>
              Verify downloads with <a href="${manifest.checksumsUrl}">SHA-256 checksums</a>.
              Runtime compatibility checks are not enforced yet; use artifacts from one release together.
            </footer>
          </main>
        </body>
        </html>
    """.trimIndent()
}

private fun io.ktor.server.application.ApplicationCall.isSecureEnrollmentTransport(): Boolean {
    val connection = request.local
    return connection.scheme.equals("https", ignoreCase = true) ||
        connection.remoteAddress in loopbackAddresses
}

private fun escapeHtml(value: String): String =
    buildString(value.length) {
        value.forEach {
            append(
                when (it) {
                    '&' -> "&amp;"
                    '<' -> "&lt;"
                    '>' -> "&gt;"
                    '"' -> "&quot;"
                    '\'' -> "&#39;"
                    else -> it
                }
            )
        }
    }

@Serializable
private data class EnrollmentError(val error: String)

private val releaseVersion = Regex("""\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?""")
private const val MAX_ENROLLMENT_REQUEST_BYTES = 8 * 1024
private const val DEVELOPMENT_SERVER_VERSION = "0.0.0-dev"
private val loopbackAddresses = setOf("127.0.0.1", "::1", "0:0:0:0:0:0:0:1")

private val distributionArtifacts = listOf(
    DistributionArtifact(
        id = "client-macos-arm64",
        component = "Client",
        operatingSystem = "macOS",
        architecture = "ARM64",
        format = "DMG",
        fileName = "gromozeka-client-macos-arm64.dmg",
        downloadPath = "/downloads/client/macos-arm64",
    ),
    DistributionArtifact(
        id = "client-windows-x64",
        component = "Client",
        operatingSystem = "Windows",
        architecture = "x64",
        format = "portable ZIP",
        fileName = "gromozeka-client-windows-x64.zip",
        downloadPath = "/downloads/client/windows-x64",
    ),
    DistributionArtifact(
        id = "worker-macos-arm64",
        component = "Worker",
        operatingSystem = "macOS",
        architecture = "ARM64",
        format = "tar.gz",
        fileName = "gromozeka-worker-macos-arm64.tar.gz",
        downloadPath = "/downloads/worker/macos-arm64",
    ),
    DistributionArtifact(
        id = "worker-windows-x64",
        component = "Worker",
        operatingSystem = "Windows",
        architecture = "x64",
        format = "portable ZIP",
        fileName = "gromozeka-worker-windows-x64.zip",
        downloadPath = "/downloads/worker/windows-x64",
    ),
    DistributionArtifact(
        id = "worker-linux-x64",
        component = "Worker",
        operatingSystem = "Linux",
        architecture = "x64",
        format = "tar.gz",
        fileName = "gromozeka-worker-linux-x64.tar.gz",
        downloadPath = "/downloads/worker/linux-x64",
    ),
)
