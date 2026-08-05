package com.gromozeka.presentation.services

import com.gromozeka.client.RemoteDistributionService
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.WorkerCatalogEntry
import com.gromozeka.domain.service.WorkerCatalogService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class DesktopLocalWorkerController(
    private val userHome: Path = Path.of(System.getProperty("user.home")),
    private val osName: String = System.getProperty("os.name"),
    private val environment: Map<String, String> = System.getenv(),
) : LocalWorkerController {
    private val supported = osName.lowercase().contains("mac")
    private val workerHome = userHome.resolve(".gromozeka")
    private val workerConfig = workerHome.resolve("worker.yaml")
    private val launchAgent = userHome.resolve("Library/LaunchAgents/com.gromozeka.worker.plist")
    private val stableLauncher = userHome.resolve(
        "Library/Application Support/Gromozeka/worker/launcher/Gromozeka Worker.app/Contents/MacOS/Gromozeka Worker"
    )
    private val mutex = Mutex()
    private val mutableStatus = MutableStateFlow(LocalWorkerStatus(supported = supported))

    override val status: StateFlow<LocalWorkerStatus> = mutableStatus.asStateFlow()

    override suspend fun initialize() = mutex.withLock {
        if (!supported) return@withLock
        runOperation(LocalWorkerOperation.REFRESHING) {
            if (Files.isRegularFile(launchAgent) && !serviceRunning()) {
                runService("start")
            }
            refreshStatus()
        }
    }

    override suspend fun refresh(workerCatalogService: WorkerCatalogService?) = mutex.withLock {
        if (!supported) return@withLock
        runOperation(LocalWorkerOperation.REFRESHING) {
            refreshStatus(workerCatalogService)
        }
    }

    override suspend fun enable(
        distributionService: RemoteDistributionService,
        workerCatalogService: WorkerCatalogService,
    ) = mutex.withLock {
        if (!supported) return@withLock
        runOperation(LocalWorkerOperation.ENROLLING) {
            requireStableInstallationPath()
            if (!Files.isRegularFile(workerConfig)) {
                val enrollment = distributionService.createWorkerEnrollmentRequest()
                val workerId = defaultWorkerId()
                runWorker(
                    arguments = listOf(
                        "enroll",
                        "--server", enrollment.serverUrl,
                        "--worker-id", workerId,
                        "--config", workerConfig.toString(),
                    ),
                    enrollmentToken = enrollment.token,
                )
            }
            mutableStatus.value = mutableStatus.value.copy(operation = LocalWorkerOperation.STARTING)
            runService("install")
            refreshStatus(workerCatalogService)
        }
    }

    override suspend fun disable() = mutex.withLock {
        if (!supported) return@withLock
        runOperation(LocalWorkerOperation.STOPPING) {
            if (Files.exists(launchAgent)) {
                runService("uninstall")
            }
            refreshStatus()
        }
    }

    override suspend fun start() = mutex.withLock {
        if (!supported) return@withLock
        runOperation(LocalWorkerOperation.STARTING) {
            require(Files.isRegularFile(launchAgent)) { "Local Worker is not enabled" }
            runService("start")
            refreshStatus()
        }
    }

    override suspend fun stop() = mutex.withLock {
        if (!supported) return@withLock
        runOperation(LocalWorkerOperation.STOPPING) {
            if (Files.isRegularFile(launchAgent)) {
                runService("stop")
            }
            refreshStatus()
        }
    }

    override suspend fun requestComputerUsePermissions() = mutex.withLock {
        if (!supported) return@withLock
        runOperation(LocalWorkerOperation.REQUESTING_PERMISSIONS) {
            runService("open-permissions")
            refreshStatus()
        }
    }

    override suspend fun stopForApplicationExit() = mutex.withLock {
        if (!supported || !serviceRunning()) return@withLock
        runCatching { runService("stop") }
        refreshStatus()
    }

    private suspend fun runOperation(
        operation: LocalWorkerOperation,
        block: suspend () -> Unit,
    ) {
        mutableStatus.value = mutableStatus.value.copy(operation = operation, failure = null)
        try {
            block()
            mutableStatus.value = mutableStatus.value.copy(operation = null, failure = null)
        } catch (error: CancellationException) {
            mutableStatus.value = mutableStatus.value.copy(operation = null)
            throw error
        } catch (error: Throwable) {
            mutableStatus.value = mutableStatus.value.copy(
                operation = null,
                failure = error.message ?: error::class.simpleName.orEmpty(),
            )
        }
    }

    private suspend fun refreshStatus(workerCatalogService: WorkerCatalogService? = null) {
        val installed = Files.isRegularFile(launchAgent)
        val running = installed && serviceRunning()
        val workerId = readWorkerId()
        val serverStatus = if (workerCatalogService != null && workerId != null) {
            runCatching {
                workerCatalogService.listWorkers()
                    .firstOrNull { it.workerId == workerId }
                    ?.status
            }.getOrNull()
        } else {
            mutableStatus.value.serverStatus.takeIf { running }
        }
        mutableStatus.value = mutableStatus.value.copy(
            installed = installed,
            running = running,
            workerId = workerId,
            serverStatus = serverStatus,
            permissions = readPermissions(),
        )
    }

    private suspend fun serviceRunning(): Boolean =
        runCatching { runService("status", requireSuccess = false).exitCode == 0 }
            .getOrDefault(false)

    private suspend fun readPermissions(): LocalWorkerPermissions {
        if (!Files.isExecutable(stableLauncher)) return LocalWorkerPermissions()
        val result = runCatching { runService("permissions-status", requireSuccess = false) }.getOrNull()
            ?: return LocalWorkerPermissions()
        if (result.exitCode != 0) return LocalWorkerPermissions()
        return runCatching {
            val values = Json.parseToJsonElement(result.output).jsonObject
            LocalWorkerPermissions(
                screenRecording = values["screenRecording"].permissionState(),
                accessibility = values["accessibility"].permissionState(),
            )
        }.getOrDefault(LocalWorkerPermissions())
    }

    private fun kotlinx.serialization.json.JsonElement?.permissionState(): LocalWorkerPermissionState =
        when (this?.jsonPrimitive?.booleanOrNull) {
            true -> LocalWorkerPermissionState.GRANTED
            false -> LocalWorkerPermissionState.NOT_GRANTED
            null -> LocalWorkerPermissionState.UNKNOWN
        }

    private fun readWorkerId(): ConversationRuntimeWorkerId? {
        if (!Files.isRegularFile(workerConfig)) return null
        val lines = runCatching { Files.readAllLines(workerConfig) }.getOrNull() ?: return null
        val runtimeIndex = lines.indexOfFirst { it.trim() == "runtime:" }
        if (runtimeIndex < 0) return null
        val runtimeIndent = lines[runtimeIndex].indentation()
        val workerIndex = lines.indices.firstOrNull { index ->
            index > runtimeIndex &&
                lines[index].indentation() > runtimeIndent &&
                lines[index].trim() == "worker:"
        } ?: return null
        val workerIndent = lines[workerIndex].indentation()
        val idLine = lines.drop(workerIndex + 1)
            .takeWhile { it.isBlank() || it.indentation() > workerIndent }
            .firstOrNull { it.trimStart().startsWith("id:") }
            ?: return null
        val encoded = idLine.substringAfter("id:").trim()
        val value = runCatching { Json.decodeFromString<String>(encoded) }
            .getOrElse { encoded.trim('"', '\'') }
            .takeIf(String::isNotBlank)
            ?: return null
        return ConversationRuntimeWorkerId(value)
    }

    private fun String.indentation(): Int = indexOfFirst { !it.isWhitespace() }.let { if (it < 0) length else it }

    private suspend fun runService(command: String, requireSuccess: Boolean = true): CommandResult =
        runCommand(
            command = listOf("/bin/bash", bundleRoot().resolve("bin/gromozeka-worker-service").toString(), command),
            description = "Local Worker command: $command",
            requireSuccess = requireSuccess,
        )

    private suspend fun runWorker(
        arguments: List<String>,
        enrollmentToken: String? = null,
    ): CommandResult = runCommand(
        command = listOf("/bin/bash", bundleRoot().resolve("bin/gromozeka-worker").toString()) + arguments,
        description = "Local Worker ${arguments.firstOrNull().orEmpty()}",
        enrollmentToken = enrollmentToken,
    )

    private suspend fun runCommand(
        command: List<String>,
        description: String,
        requireSuccess: Boolean = true,
        enrollmentToken: String? = null,
    ): CommandResult =
        withContext(Dispatchers.IO) {
            require(Files.isRegularFile(Path.of(command[1]))) { "Bundled Local Worker executable is missing: ${command[1]}" }
            val outputFile = Files.createTempFile("gromozeka-local-worker-", ".log")
            try {
                val processBuilder = ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(outputFile.toFile())
                processBuilder.environment()["GROMOZEKA_HOME"] = workerHome.toString()
                processBuilder.environment()["GROMOZEKA_WORKER_CONFIG"] = workerConfig.toString()
                enrollmentToken?.let {
                    processBuilder.environment()[WORKER_ENROLLMENT_TOKEN_ENVIRONMENT_VARIABLE] = it
                }
                if (!isPackagedApplication()) {
                    processBuilder.environment()["GROMOZEKA_JAVA_EXECUTABLE"] =
                        Path.of(System.getProperty("java.home"), "bin", "java").toString()
                    processBuilder.environment()["GROMOZEKA_WORKER_JAVA_CLASSPATH"] =
                        System.getProperty("java.class.path")
                }
                val process = processBuilder.start()
                val completed = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                if (!completed) {
                    process.destroyForcibly()
                    error("$description timed out")
                }
                val result = CommandResult(process.exitValue(), Files.readString(outputFile).trim())
                if (requireSuccess && result.exitCode != 0) {
                    error(result.output.ifBlank { "$description failed" })
                }
                result
            } finally {
                Files.deleteIfExists(outputFile)
            }
        }

    private fun bundleRoot(): Path {
        val explicit = System.getProperty("gromozeka.local-worker.bundle-root")
            ?.takeIf(String::isNotBlank)
            ?.let(Path::of)
        val packaged = System.getProperty("compose.application.resources.dir")
            ?.takeIf(String::isNotBlank)
            ?.let(Path::of)
            ?.resolve("local-worker")
        return explicit ?: packaged
            ?: error("Local Worker resources are available only in a packaged app or the configured development run")
    }

    private fun isPackagedApplication(): Boolean =
        !System.getProperty("compose.application.resources.dir").isNullOrBlank()

    private fun requireStableInstallationPath() {
        val resourcesDirectory = System.getProperty("compose.application.resources.dir") ?: return
        require(!Path.of(resourcesDirectory).toAbsolutePath().startsWith(Path.of("/Volumes"))) {
            "Move Gromozeka to Applications before enabling the Local Worker"
        }
    }

    private fun defaultWorkerId(): String {
        val raw = environment["HOSTNAME"]
            ?.substringBefore('.')
            ?.takeIf(String::isNotBlank)
            ?: "${System.getProperty("user.name")}-mac"
        val normalized = raw.lowercase()
            .replace(Regex("[^a-z0-9._-]+"), "-")
            .trim('-', '.', '_')
            .take(MAX_WORKER_ID_LENGTH)
        return normalized.ifBlank { "local-mac" }
    }

    private data class CommandResult(val exitCode: Int, val output: String)

    private companion object {
        const val COMMAND_TIMEOUT_SECONDS = 30L
        const val MAX_WORKER_ID_LENGTH = 64
        const val WORKER_ENROLLMENT_TOKEN_ENVIRONMENT_VARIABLE = "GROMOZEKA_WORKER_ENROLLMENT_TOKEN"
    }
}
