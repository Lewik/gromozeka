package com.gromozeka.presentation.services

import com.gromozeka.client.RemoteDistributionService
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.WorkerCatalogEntry
import com.gromozeka.domain.service.WorkerCatalogService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import com.gromozeka.remote.protocol.DeviceConnectionWorkerRequest
import com.gromozeka.remote.protocol.WorkerEnrollmentBootstrap
import java.nio.file.Files
import java.nio.file.Path

class DesktopLocalWorkerController(
    userHome: Path = Path.of(System.getProperty("user.home")),
    osName: String = System.getProperty("os.name"),
    environment: Map<String, String> = System.getenv(),
) : LocalWorkerController {
    private val runtime = createDesktopLocalWorkerRuntime(userHome, osName, environment)
    private val mutex = Mutex()
    private val mutableStatus = MutableStateFlow(
        LocalWorkerStatus(
            supported = runtime != null,
            deviceDisplayName = runtime?.deviceDisplayName ?: "device",
        )
    )

    override val status: StateFlow<LocalWorkerStatus> = mutableStatus.asStateFlow()

    override suspend fun initialize() = mutex.withLock {
        val runtime = runtime ?: return@withLock
        runOperation(LocalWorkerOperation.REFRESHING) {
            if (runtime.isEnabled() && !Files.isRegularFile(runtime.workerConfig)) {
                runtime.disable()
            }
            if (runtime.isEnabled() && !runtime.isRunning()) {
                runtime.start()
            }
            refreshStatus()
        }
    }

    override suspend fun refresh(workerCatalogService: WorkerCatalogService?) = mutex.withLock {
        if (runtime == null) return@withLock
        runOperation(LocalWorkerOperation.REFRESHING) {
            refreshStatus(workerCatalogService)
        }
    }

    override suspend fun enable(
        distributionService: RemoteDistributionService,
        workerCatalogService: WorkerCatalogService,
    ) = mutex.withLock {
        val runtime = runtime ?: return@withLock
        runOperation(LocalWorkerOperation.ENROLLING) {
            runtime.requireStableInstallationPath()
            if (!Files.isRegularFile(runtime.workerConfig)) {
                val enrollment = distributionService.createWorkerEnrollmentRequest()
                runtime.enroll(
                    arguments = listOf(
                        "enroll",
                        "--server", enrollment.serverUrl,
                        "--worker-id", defaultWorkerId(runtime),
                        "--config", runtime.workerConfig.toString(),
                    ),
                    enrollmentToken = enrollment.token,
                )
            }
            mutableStatus.value = mutableStatus.value.copy(operation = LocalWorkerOperation.STARTING)
            runtime.enable()
            refreshStatus(workerCatalogService)
        }
    }

    override suspend fun disable() = mutex.withLock {
        val runtime = runtime ?: return@withLock
        runOperation(LocalWorkerOperation.STOPPING) {
            runtime.disable()
            refreshStatus()
        }
    }

    override suspend fun start() = mutex.withLock {
        val runtime = runtime ?: return@withLock
        runOperation(LocalWorkerOperation.STARTING) {
            require(runtime.isEnabled()) { "Local Worker is not enabled" }
            runtime.start()
            refreshStatus()
        }
    }

    override suspend fun stop() = mutex.withLock {
        val runtime = runtime ?: return@withLock
        runOperation(LocalWorkerOperation.STOPPING) {
            runtime.stop()
            refreshStatus()
        }
    }

    override suspend fun requestComputerUsePermissions() = mutex.withLock {
        val runtime = runtime ?: return@withLock
        if (!runtime.computerUsePermissionsSupported) return@withLock
        runOperation(LocalWorkerOperation.REQUESTING_PERMISSIONS) {
            runtime.requestComputerUsePermissions()
            refreshStatus()
        }
    }

    override suspend fun stopForApplicationExit() = mutex.withLock {
        val runtime = runtime ?: return@withLock
        if (!runtime.isRunning()) return@withLock
        runCatching { runtime.stop() }
        refreshStatus()
    }

    override fun deviceConnectionWorkerRequest(): DeviceConnectionWorkerRequest? {
        val runtime = runtime ?: return null
        if (Files.isRegularFile(runtime.workerConfig)) return null
        return DeviceConnectionWorkerRequest(workerId = defaultWorkerId(runtime))
    }

    override suspend fun acceptDeviceConnection(
        serverUrl: String,
        bootstrap: WorkerEnrollmentBootstrap,
        workerCatalogService: WorkerCatalogService,
    ) = mutex.withLock {
        val runtime = runtime ?: return@withLock
        runOperation(LocalWorkerOperation.ENROLLING) {
            runtime.requireStableInstallationPath()
            if (!Files.isRegularFile(runtime.workerConfig)) {
                runtime.configure(
                    arguments = listOf(
                        "configure",
                        "--server", serverUrl,
                        "--config", runtime.workerConfig.toString(),
                    ),
                    bootstrap = Json.encodeToString(bootstrap),
                )
            }
            mutableStatus.value = mutableStatus.value.copy(operation = LocalWorkerOperation.STARTING)
            if (runtime.isEnabled()) {
                if (!runtime.isRunning()) runtime.start()
            } else {
                runtime.enable()
            }
            refreshStatus(workerCatalogService)
        }
    }

    override fun close() {
        runtime?.close()
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
        val runtime = runtime ?: return
        val installed = runtime.isEnabled()
        val running = installed && runtime.isRunning()
        val workerId = readWorkerId(runtime.workerConfig)
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
            permissions = runtime.readComputerUsePermissions(),
        )
    }

    private fun readWorkerId(workerConfig: Path): ConversationRuntimeWorkerId? {
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

    private fun defaultWorkerId(runtime: DesktopLocalWorkerRuntime): String {
        val host = runtime.hostName()
            ?.substringBefore('.')
            ?.takeIf(String::isNotBlank)
            ?: System.getProperty("user.name")
        val normalizedHost = host.lowercase()
            .replace(Regex("[^a-z0-9._-]+"), "-")
            .trim('-', '.', '_')
        val suffix = "-local"
        return normalizedHost
            .take(MAX_WORKER_ID_LENGTH - suffix.length)
            .ifBlank { runtime.workerIdSuffix }
            .plus(suffix)
    }

    private companion object {
        const val MAX_WORKER_ID_LENGTH = 64
    }
}
