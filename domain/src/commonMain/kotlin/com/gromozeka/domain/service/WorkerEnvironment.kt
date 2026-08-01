package com.gromozeka.domain.service

import com.gromozeka.domain.model.WorkspaceMount
import com.gromozeka.domain.model.WorkerAudioInput
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class WorkerEnvironmentProfile(
    val observedAt: Instant,
    val operatingSystem: WorkerOperatingSystem,
    val architecture: String,
    val nativeShell: WorkerNativeShell,
    val timezoneId: String,
    val localeTag: String,
    val logicalProcessorCount: Int,
    val totalMemoryBytes: Long?,
    val availableExecutables: List<String>,
    val audioInputs: List<WorkerAudioInput> = emptyList(),
) {
    init {
        require(architecture.isNotBlank()) { "Worker architecture must not be blank" }
        require(timezoneId.isNotBlank()) { "Worker timezone must not be blank" }
        require(localeTag.isNotBlank()) { "Worker locale must not be blank" }
        require(logicalProcessorCount > 0) { "Worker logical processor count must be positive" }
        require(totalMemoryBytes == null || totalMemoryBytes >= 0) {
            "Worker total memory must be non-negative"
        }
        require(availableExecutables == availableExecutables.distinct().sorted()) {
            "Worker executable names must be unique and sorted"
        }
        require(availableExecutables.all(String::isNotBlank)) {
            "Worker executable names must not be blank"
        }
        require(audioInputs.map { it.id }.distinct().size == audioInputs.size) {
            "Worker audio input ids must be unique"
        }
        require(audioInputs.count { it.isDefault } <= 1) {
            "Worker environment must not advertise several default audio inputs"
        }
    }
}

@Serializable
data class WorkerOperatingSystem(
    val family: Family,
    val name: String,
    val version: String,
) {
    init {
        require(name.isNotBlank()) { "Worker operating system name must not be blank" }
        require(version.isNotBlank()) { "Worker operating system version must not be blank" }
    }

    @Serializable
    enum class Family {
        LINUX,
        MACOS,
        WINDOWS,
        OTHER,
    }
}

@Serializable
data class WorkerNativeShell(
    val kind: Kind,
    val executable: String,
) {
    init {
        require(executable.isNotBlank()) { "Worker native shell executable must not be blank" }
    }

    @Serializable
    enum class Kind {
        POSIX_SH,
        WINDOWS_CMD,
    }
}

@Serializable
data class WorkerEnvironmentSnapshot(
    val profile: WorkerEnvironmentProfile,
    val workerProcessUptimeMillis: Long,
    val availableMemoryBytes: Long?,
    val systemCpuLoadRatio: Double?,
    val requestedExecutables: List<WorkerExecutableAvailability>,
    val workspaceStorage: List<WorkerWorkspaceStorage>,
) {
    init {
        require(workerProcessUptimeMillis >= 0) { "Worker process uptime must be non-negative" }
        require(availableMemoryBytes == null || availableMemoryBytes >= 0) {
            "Worker available memory must be non-negative"
        }
        require(systemCpuLoadRatio == null || systemCpuLoadRatio in 0.0..1.0) {
            "Worker system CPU load must be between zero and one"
        }
        require(requestedExecutables.map { it.name } == requestedExecutables.map { it.name }.distinct().sorted()) {
            "Requested executable results must be unique and sorted"
        }
        require(workspaceStorage.map { it.workspaceMountId.value }.distinct().size == workspaceStorage.size) {
            "Worker workspace storage entries must have unique mount IDs"
        }
    }
}

@Serializable
data class WorkerExecutableAvailability(
    val name: String,
    val available: Boolean,
) {
    init {
        require(name.isNotBlank()) { "Executable name must not be blank" }
    }
}

@Serializable
data class WorkerWorkspaceStorage(
    val workspaceMountId: WorkspaceMount.Id,
    val rootPath: String,
    val accessible: Boolean,
    val totalBytes: Long?,
    val usableBytes: Long?,
) {
    init {
        require(rootPath.isNotBlank()) { "Worker workspace storage root path must not be blank" }
        require(totalBytes == null || totalBytes >= 0) { "Workspace storage total bytes must be non-negative" }
        require(usableBytes == null || usableBytes >= 0) { "Workspace storage usable bytes must be non-negative" }
        require(accessible || (totalBytes == null && usableBytes == null)) {
            "Inaccessible workspace storage must not report capacity"
        }
        require(totalBytes == null || usableBytes == null || usableBytes <= totalBytes) {
            "Workspace usable storage must not exceed total storage"
        }
    }
}

interface WorkerEnvironmentProbe {
    fun collectProfile(): WorkerEnvironmentProfile

    fun collectSnapshot(
        workspaceMounts: List<WorkspaceMount>,
        requestedExecutableNames: Set<String>,
    ): WorkerEnvironmentSnapshot
}

const val MAX_WORKER_ENVIRONMENT_EXECUTABLE_REQUESTS = 32
