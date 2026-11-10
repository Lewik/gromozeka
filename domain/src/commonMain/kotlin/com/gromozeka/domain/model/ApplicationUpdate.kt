package com.gromozeka.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class UpdateComponent { CLIENT, WORKER }

@Serializable
enum class UpdatePlatform { MACOS_ARM64, WINDOWS_X64, LINUX_X64, ANDROID }

@Serializable
enum class UpdatePackageFormat { MACOS_APP_ZIP, WINDOWS_ZIP, WORKER_TAR_GZ, APK }

@Serializable
data class ApplicationUpdateArtifact(
    val component: UpdateComponent,
    val platform: UpdatePlatform,
    val version: String,
    val fileName: String,
    val size: Long,
    val sha256: String,
    val format: UpdatePackageFormat,
) {
    init {
        require(UPDATE_VERSION.matches(version)) { "Invalid update version" }
        require(Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,180}").matches(fileName)) { "Invalid update file name" }
        require(size in 1..MAX_UPDATE_PACKAGE_BYTES) { "Invalid update package size" }
        require(Regex("[a-f0-9]{64}").matches(sha256)) { "Invalid update checksum" }
        require(when (format) {
            UpdatePackageFormat.APK -> platform == UpdatePlatform.ANDROID
            UpdatePackageFormat.WINDOWS_ZIP -> platform == UpdatePlatform.WINDOWS_X64
            UpdatePackageFormat.MACOS_APP_ZIP -> platform == UpdatePlatform.MACOS_ARM64 && component == UpdateComponent.CLIENT
            UpdatePackageFormat.WORKER_TAR_GZ -> platform in setOf(UpdatePlatform.MACOS_ARM64, UpdatePlatform.LINUX_X64) && component == UpdateComponent.WORKER
        }) { "Update package does not match its platform" }
    }
}

@Serializable
data class ApplicationUpdateCatalog(
    val schemaVersion: Int = 1,
    val version: String,
    val artifacts: List<ApplicationUpdateArtifact>,
) {
    init {
        require(schemaVersion == 1 && UPDATE_VERSION.matches(version))
        require(artifacts.size in 1..16 && artifacts.all { it.version == version })
        require(artifacts.map { it.component to it.platform }.distinct().size == artifacts.size)
        require(artifacts.map { it.fileName }.distinct().size == artifacts.size)
    }
}

@Serializable
enum class ApplicationUpdatePhase {
    QUEUED, WAITING_FOR_IDLE, DOWNLOADING, READY, WAITING_FOR_USER, INSTALLING, SUCCEEDED, FAILED, CANCELLED;

    val terminal: Boolean get() = this in setOf(SUCCEEDED, FAILED, CANCELLED)
}

@Serializable
data class ApplicationUpdateReport(
    val requestId: String,
    val phase: ApplicationUpdatePhase,
    val message: String? = null,
) {
    init { require(requestId.length in 1..80); require(message == null || message.length <= 512) }
}

@Serializable
data class ApplicationUpdateRequest(
    val id: String,
    val artifact: ApplicationUpdateArtifact,
    val requestedAt: String,
    val phase: ApplicationUpdatePhase = ApplicationUpdatePhase.QUEUED,
    val message: String? = null,
)

@Serializable
data class ApplicationInstallation(
    val id: String,
    val ownerUserId: User.Id,
    val workerId: String? = null,
    val component: UpdateComponent,
    val platform: UpdatePlatform,
    val label: String,
    val version: String,
    val lastSeenAt: String,
    val automatic: Boolean = false,
    val unsupportedReason: String? = null,
    val request: ApplicationUpdateRequest? = null,
) {
    init {
        require(Regex("[a-zA-Z0-9_-]{1,100}").matches(id))
        require(label.isNotBlank() && label.length <= 128)
        require(version.isNotBlank() && version.length <= 80)
        require(unsupportedReason == null || unsupportedReason.length <= 256)
    }
}

val UPDATE_VERSION = Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?")
const val MAX_UPDATE_PACKAGE_BYTES = 2L * 1024 * 1024 * 1024
