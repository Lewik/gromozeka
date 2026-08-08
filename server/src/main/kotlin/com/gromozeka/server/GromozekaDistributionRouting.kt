package com.gromozeka.server

import com.gromozeka.domain.model.mcp.BrowserUseMcpPreset
import com.gromozeka.remote.protocol.DistributionArchitecture
import com.gromozeka.remote.protocol.DistributionArtifact
import com.gromozeka.remote.protocol.DistributionComponent
import com.gromozeka.remote.protocol.DistributionFormat
import com.gromozeka.remote.protocol.DistributionManifest
import com.gromozeka.remote.protocol.DistributionOperatingSystem
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.gromozeka.remote.protocol.WorkerEnrollmentConsumeRequest
import com.gromozeka.domain.service.AuthenticationService

private const val DEFAULT_RELEASE_REPOSITORY = "Lewik/gromozeka"

internal fun Routing.gromozekaDistributions(
    workerEnrollmentService: WorkerEnrollmentService,
    authenticationService: AuthenticationService,
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
        artifacts = distributionArtifacts(releaseDownloadBaseUrl),
        checksumsUrl = "$releaseDownloadBaseUrl/SHA256SUMS",
        workerEnrollment = workerEnrollmentService.availability(),
    )
    val manifestJson = Json {
        prettyPrint = true
        encodeDefaults = true
    }.encodeToString(manifest)

    get("/api/distributions") {
        call.respondText(manifestJson, ContentType.Application.Json)
    }
    post("/api/worker-enrollments") {
        val authenticatedSession = call.authenticateOrNull(authenticationService)
        if (authenticatedSession == null) {
            call.respondText(
                """{"error":"Authentication required"}""",
                ContentType.Application.Json,
                HttpStatusCode.Unauthorized,
            )
            return@post
        }
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        if (!call.isSecureTransport()) {
            call.respondText(
                """{"error":"Worker enrollment requires HTTPS"}""",
                ContentType.Application.Json,
                HttpStatusCode.UpgradeRequired,
            )
            return@post
        }
        runCatching { workerEnrollmentService.create(authenticatedSession.user.id) }
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
        if (!call.isSecureTransport()) {
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
            workerEnrollmentService.consume(request.token, request.workerId, request.kind)
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

@Serializable
private data class EnrollmentError(val error: String)

private val releaseVersion = Regex("""\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?""")
private const val MAX_ENROLLMENT_REQUEST_BYTES = 8 * 1024
private const val DEVELOPMENT_SERVER_VERSION = "0.0.0-dev"

internal fun distributionArtifacts(releaseDownloadBaseUrl: String) = listOf(
    DistributionArtifact(
        id = BrowserUseMcpPreset.BRIDGE_ARTIFACT_ID,
        component = DistributionComponent.BROWSER_BRIDGE,
        operatingSystem = DistributionOperatingSystem.ANY,
        architecture = DistributionArchitecture.ANY,
        format = DistributionFormat.BROWSER_EXTENSION_ZIP,
        fileName = BrowserUseMcpPreset.BRIDGE_FILE_NAME,
        downloadUrl = "$releaseDownloadBaseUrl/${BrowserUseMcpPreset.BRIDGE_FILE_NAME}",
    ),
    DistributionArtifact(
        id = "client-macos-arm64",
        component = DistributionComponent.CLIENT,
        operatingSystem = DistributionOperatingSystem.MACOS,
        architecture = DistributionArchitecture.ARM64,
        format = DistributionFormat.DMG,
        fileName = "gromozeka-client-macos-arm64.dmg",
        downloadUrl = "$releaseDownloadBaseUrl/gromozeka-client-macos-arm64.dmg",
    ),
    DistributionArtifact(
        id = "client-windows-x64",
        component = DistributionComponent.CLIENT,
        operatingSystem = DistributionOperatingSystem.WINDOWS,
        architecture = DistributionArchitecture.X64,
        format = DistributionFormat.PORTABLE_ZIP,
        fileName = "gromozeka-client-windows-x64.zip",
        downloadUrl = "$releaseDownloadBaseUrl/gromozeka-client-windows-x64.zip",
    ),
    DistributionArtifact(
        id = "server-docker-compose",
        component = DistributionComponent.SERVER,
        operatingSystem = DistributionOperatingSystem.ANY,
        architecture = DistributionArchitecture.ANY,
        format = DistributionFormat.DOCKER_COMPOSE_ZIP,
        fileName = "gromozeka-server-stack.zip",
        downloadUrl = "$releaseDownloadBaseUrl/gromozeka-server-stack.zip",
    ),
    DistributionArtifact(
        id = "server-macos-arm64",
        component = DistributionComponent.SERVER,
        operatingSystem = DistributionOperatingSystem.MACOS,
        architecture = DistributionArchitecture.ARM64,
        format = DistributionFormat.TAR_GZ,
        fileName = "gromozeka-server-macos-arm64.tar.gz",
        downloadUrl = "$releaseDownloadBaseUrl/gromozeka-server-macos-arm64.tar.gz",
    ),
    DistributionArtifact(
        id = "server-windows-x64",
        component = DistributionComponent.SERVER,
        operatingSystem = DistributionOperatingSystem.WINDOWS,
        architecture = DistributionArchitecture.X64,
        format = DistributionFormat.PORTABLE_ZIP,
        fileName = "gromozeka-server-windows-x64.zip",
        downloadUrl = "$releaseDownloadBaseUrl/gromozeka-server-windows-x64.zip",
    ),
    DistributionArtifact(
        id = "server-linux-x64",
        component = DistributionComponent.SERVER,
        operatingSystem = DistributionOperatingSystem.LINUX,
        architecture = DistributionArchitecture.X64,
        format = DistributionFormat.TAR_GZ,
        fileName = "gromozeka-server-linux-x64.tar.gz",
        downloadUrl = "$releaseDownloadBaseUrl/gromozeka-server-linux-x64.tar.gz",
    ),
    DistributionArtifact(
        id = "worker-macos-arm64",
        component = DistributionComponent.WORKER,
        operatingSystem = DistributionOperatingSystem.MACOS,
        architecture = DistributionArchitecture.ARM64,
        format = DistributionFormat.TAR_GZ,
        fileName = "gromozeka-worker-macos-arm64.tar.gz",
        downloadUrl = "$releaseDownloadBaseUrl/gromozeka-worker-macos-arm64.tar.gz",
    ),
    DistributionArtifact(
        id = "worker-windows-x64",
        component = DistributionComponent.WORKER,
        operatingSystem = DistributionOperatingSystem.WINDOWS,
        architecture = DistributionArchitecture.X64,
        format = DistributionFormat.PORTABLE_ZIP,
        fileName = "gromozeka-worker-windows-x64.zip",
        downloadUrl = "$releaseDownloadBaseUrl/gromozeka-worker-windows-x64.zip",
    ),
    DistributionArtifact(
        id = "worker-linux-x64",
        component = DistributionComponent.WORKER,
        operatingSystem = DistributionOperatingSystem.LINUX,
        architecture = DistributionArchitecture.X64,
        format = DistributionFormat.TAR_GZ,
        fileName = "gromozeka-worker-linux-x64.tar.gz",
        downloadUrl = "$releaseDownloadBaseUrl/gromozeka-worker-linux-x64.tar.gz",
    ),
)
