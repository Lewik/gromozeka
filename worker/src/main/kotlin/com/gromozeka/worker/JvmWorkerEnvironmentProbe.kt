package com.gromozeka.worker

import com.gromozeka.domain.model.WorkspaceMount
import com.gromozeka.domain.service.WorkerEnvironmentProbe
import com.gromozeka.domain.service.WorkerEnvironmentProfile
import com.gromozeka.domain.service.WorkerEnvironmentSnapshot
import com.gromozeka.domain.service.WorkerExecutableAvailability
import com.gromozeka.domain.service.WorkerNativeShell
import com.gromozeka.domain.service.WorkerOperatingSystem
import com.gromozeka.domain.service.WorkerWorkspaceStorage
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import java.io.File
import java.lang.management.ManagementFactory
import java.time.ZoneId
import java.util.Locale

class JvmWorkerEnvironmentProbe internal constructor(
    executableNames: Set<String>,
    private val host: WorkerHost = JvmWorkerHost(),
    private val now: () -> Instant = { Clock.System.now() },
) : WorkerEnvironmentProbe {
    private val profileExecutableNames = executableNames.normalizedExecutableNames()

    override fun collectProfile(): WorkerEnvironmentProfile =
        WorkerEnvironmentProfile(
            observedAt = now(),
            operatingSystem = WorkerOperatingSystem(
                family = host.osFamily(),
                name = host.osName.ifBlank { UNKNOWN_VALUE },
                version = host.osVersion.ifBlank { UNKNOWN_VALUE },
            ),
            architecture = normalizeArchitecture(host.architecture),
            nativeShell = host.nativeShell(),
            timezoneId = host.timezoneId.ifBlank { UNKNOWN_VALUE },
            localeTag = host.localeTag.ifBlank { UNKNOWN_VALUE },
            logicalProcessorCount = host.logicalProcessorCount.coerceAtLeast(1),
            totalMemoryBytes = host.totalMemoryBytes?.takeIf { it >= 0 },
            availableExecutables = profileExecutableNames
                .filter(host::isExecutableAvailable),
        )

    override fun collectSnapshot(
        workspaceMounts: List<WorkspaceMount>,
        requestedExecutableNames: Set<String>,
    ): WorkerEnvironmentSnapshot {
        val requested = requestedExecutableNames.normalizedExecutableNames()
        return WorkerEnvironmentSnapshot(
            profile = collectProfile(),
            workerProcessUptimeMillis = host.workerProcessUptimeMillis.coerceAtLeast(0),
            availableMemoryBytes = host.availableMemoryBytes?.takeIf { it >= 0 },
            systemCpuLoadRatio = host.systemCpuLoad?.takeIf { it in 0.0..1.0 },
            requestedExecutables = requested.map { name ->
                WorkerExecutableAvailability(
                    name = name,
                    available = host.isExecutableAvailable(name),
                )
            },
            workspaceStorage = workspaceMounts
                .distinctBy { it.id }
                .sortedBy { it.id.value }
                .map(host::workspaceStorage),
        )
    }
}

internal interface WorkerHost {
    val osName: String
    val osVersion: String
    val architecture: String
    val timezoneId: String
    val localeTag: String
    val logicalProcessorCount: Int
    val windowsCommandInterpreter: String
    val totalMemoryBytes: Long?
    val availableMemoryBytes: Long?
    val systemCpuLoad: Double?
    val workerProcessUptimeMillis: Long

    fun isExecutableAvailable(name: String): Boolean

    fun workspaceStorage(mount: WorkspaceMount): WorkerWorkspaceStorage
}

internal class JvmWorkerHost : WorkerHost {
    private val operatingSystemBean = ManagementFactory.getOperatingSystemMXBean()
    private val extendedOperatingSystemBean = operatingSystemBean as? com.sun.management.OperatingSystemMXBean
    private val windows = osFamily() == WorkerOperatingSystem.Family.WINDOWS

    override val osName: String
        get() = System.getProperty("os.name").orEmpty().trim()

    override val osVersion: String
        get() = System.getProperty("os.version").orEmpty().trim()

    override val architecture: String
        get() = System.getProperty("os.arch").orEmpty().trim()

    override val timezoneId: String
        get() = ZoneId.systemDefault().id

    override val localeTag: String
        get() = Locale.getDefault().toLanguageTag()

    override val logicalProcessorCount: Int
        get() = Runtime.getRuntime().availableProcessors()

    override val windowsCommandInterpreter: String
        get() = System.getenv("ComSpec")?.trim().orEmpty().ifBlank { "cmd.exe" }

    override val totalMemoryBytes: Long?
        get() = extendedOperatingSystemBean?.totalMemorySize

    override val availableMemoryBytes: Long?
        get() = extendedOperatingSystemBean?.freeMemorySize

    override val systemCpuLoad: Double?
        get() = extendedOperatingSystemBean?.cpuLoad

    override val workerProcessUptimeMillis: Long
        get() = ManagementFactory.getRuntimeMXBean().uptime

    override fun isExecutableAvailable(name: String): Boolean {
        val extensions = if (windows) {
            System.getenv("PATHEXT")
                ?.split(File.pathSeparatorChar)
                ?.map(String::trim)
                ?.filter(String::isNotEmpty)
                ?.ifEmpty { null }
                ?: WINDOWS_EXECUTABLE_EXTENSIONS
        } else {
            listOf("")
        }
        val candidates = if (windows && extensions.none { name.endsWith(it, ignoreCase = true) }) {
            extensions.map { extension -> "$name$extension" }
        } else {
            listOf(name)
        }
        return System.getenv("PATH")
            .orEmpty()
            .split(File.pathSeparatorChar)
            .asSequence()
            .map { it.trim().removeSurrounding("\"") }
            .filter(String::isNotEmpty)
            .flatMap { directory -> candidates.asSequence().map { candidate -> File(directory, candidate) } }
            .any { file -> file.isFile && (windows || file.canExecute()) }
    }

    override fun workspaceStorage(mount: WorkspaceMount): WorkerWorkspaceStorage {
        val root = File(mount.rootPath)
        val accessible = root.isDirectory
        return WorkerWorkspaceStorage(
            workspaceMountId = mount.id,
            rootPath = mount.rootPath,
            accessible = accessible,
            totalBytes = root.totalSpace.takeIf { accessible },
            usableBytes = root.usableSpace.takeIf { accessible },
        )
    }
}

internal fun WorkerHost.osFamily(): WorkerOperatingSystem.Family {
    val normalized = osName.lowercase()
    return when {
        "mac" in normalized || "darwin" in normalized -> WorkerOperatingSystem.Family.MACOS
        "windows" in normalized -> WorkerOperatingSystem.Family.WINDOWS
        "linux" in normalized -> WorkerOperatingSystem.Family.LINUX
        else -> WorkerOperatingSystem.Family.OTHER
    }
}

internal fun WorkerHost.nativeShell(): WorkerNativeShell =
    if (osFamily() == WorkerOperatingSystem.Family.WINDOWS) {
        WorkerNativeShell(WorkerNativeShell.Kind.WINDOWS_CMD, windowsCommandInterpreter)
    } else {
        WorkerNativeShell(WorkerNativeShell.Kind.POSIX_SH, "/bin/sh")
    }

internal fun normalizeArchitecture(value: String): String =
    when (val normalized = value.trim().lowercase()) {
        "amd64", "x86_64" -> "x86_64"
        "aarch64", "arm64" -> "arm64"
        "x86", "i386", "i486", "i586", "i686" -> "x86"
        else -> normalized.ifBlank { UNKNOWN_VALUE }
    }

private fun Set<String>.normalizedExecutableNames(): List<String> =
    map(String::trim)
        .filter(String::isNotEmpty)
        .onEach { name ->
            require(EXECUTABLE_NAME.matches(name)) {
                "Executable name must be a basename containing only letters, digits, '.', '_', '+', or '-': $name"
            }
        }
        .distinct()
        .sorted()

private val EXECUTABLE_NAME = Regex("[A-Za-z0-9._+-]{1,64}")
private val WINDOWS_EXECUTABLE_EXTENSIONS = listOf(".COM", ".EXE", ".BAT", ".CMD")
private const val UNKNOWN_VALUE = "unknown"
