package com.gromozeka.server

import com.gromozeka.domain.model.mcp.BrowserUseMcpPreset
import com.gromozeka.remote.protocol.*
import com.gromozeka.domain.service.AuthenticationService
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

private const val DEFAULT_RELEASE_REPOSITORY = "Lewik/gromozeka"

@RestController
internal class GromozekaDistributionRouting(
    private val workerEnrollmentService: WorkerEnrollmentService,
    private val authenticationService: AuthenticationService,
    private val settings: ServerHttpSettings,
) {
    private val serverVersion = currentServerVersion()
    private val baseUrl = resolveReleaseDownloadBaseUrl(serverVersion, configuredReleaseRepository(), configuredDistributionBaseUrl())
    private val manifest = DistributionManifest(serverVersion = serverVersion,
        artifacts = distributionArtifacts(baseUrl), checksumsUrl = "$baseUrl/SHA256SUMS",
        workerEnrollment = workerEnrollmentService.availability())

    @GetMapping("/api/distributions")
    fun distributions(response: HttpServletResponse) = response.writeJson(manifest)

    @PostMapping("/api/worker-enrollments")
    suspend fun enroll(request: HttpServletRequest, response: HttpServletResponse) {
        val principal = request.requireAuthenticated(authenticationService).principal
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store")
        if (!request.isSecureTransport(settings)) return response.writeHttpError(HttpStatus.UPGRADE_REQUIRED, "Worker enrollment requires HTTPS")
        try { response.writeJson(workerEnrollmentService.create(principal.user.id), HttpStatus.CREATED) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { response.writeHttpError(HttpStatus.SERVICE_UNAVAILABLE, error.message ?: "Worker enrollment is unavailable") }
    }

    @PostMapping("/api/worker-enrollments/consume")
    suspend fun consume(request: HttpServletRequest, response: HttpServletResponse) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store")
        if (!request.isSecureTransport(settings)) return response.writeHttpError(HttpStatus.UPGRADE_REQUIRED, "Worker enrollment requires HTTPS")
        val text = try { request.readBoundedBody(MAX_ENROLLMENT_REQUEST_BYTES).decodeToString(throwOnInvalidSequence = true) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: HttpRequestBodyTooLarge) { return response.writeHttpError(HttpStatus.PAYLOAD_TOO_LARGE, "Worker enrollment request is too large") }
        catch (_: Exception) { return response.writeHttpError(HttpStatus.BAD_REQUEST, "Worker enrollment request could not be read") }
        try {
            val body = Json.decodeFromString<WorkerEnrollmentConsumeRequest>(text)
            response.writeJson(workerEnrollmentService.consume(body.token, body.workerId, body.platform, body.bindToUser))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { response.writeHttpError(HttpStatus.BAD_REQUEST, error.message ?: "Worker enrollment failed") }
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
