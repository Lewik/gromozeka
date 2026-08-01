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
    SERVER,
    WORKER,
}

@Serializable
enum class DistributionOperatingSystem {
    ANY,
    MACOS,
    WINDOWS,
    LINUX,
}

@Serializable
enum class DistributionArchitecture {
    ANY,
    ARM64,
    X64,
}

@Serializable
enum class DistributionFormat {
    DOCKER_COMPOSE_ZIP,
    DMG,
    PORTABLE_ZIP,
    TAR_GZ,
}
