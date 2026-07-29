package com.gromozeka.remote.protocol

import kotlinx.serialization.Serializable

@Serializable
data class DistributionManifest(
    val serverVersion: String,
    val artifacts: List<DistributionArtifact>,
    val checksumsUrl: String,
    val workerEnrollment: WorkerEnrollmentAvailability,
)

@Serializable
data class DistributionArtifact(
    val id: String,
    val component: DistributionComponent,
    val operatingSystem: DistributionOperatingSystem,
    val architecture: DistributionArchitecture,
    val format: DistributionFormat,
    val fileName: String,
    val downloadUrl: String,
)

@Serializable
enum class DistributionComponent {
    CLIENT,
    WORKER,
}

@Serializable
enum class DistributionOperatingSystem {
    MACOS,
    WINDOWS,
    LINUX,
}

@Serializable
enum class DistributionArchitecture {
    ARM64,
    X64,
}

@Serializable
enum class DistributionFormat {
    DMG,
    PORTABLE_ZIP,
    TAR_GZ,
}
