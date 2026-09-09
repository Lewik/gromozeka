package com.gromozeka.infrastructure.ai.tool

import com.gromozeka.domain.service.CommandProcessRecovery
import com.gromozeka.domain.service.CommandProcessRecoverySpec
import com.gromozeka.domain.service.CommandProcessRunner
import com.gromozeka.domain.service.CommandProcessSpec
import com.gromozeka.domain.service.CommandOutputGarbageCollectionResult
import com.gromozeka.domain.service.CommandOutputGarbageCollectionSpec
import com.gromozeka.domain.service.RunningCommandProcess
import com.gromozeka.domain.service.CommandTask
import klog.KLoggers
import kotlin.time.Instant
import org.springframework.stereotype.Service
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@Service
class LocalCommandProcessRunner : CommandProcessRunner {
    private val host: LocalCommandHost by lazy(::currentLocalCommandHost)

    override fun start(spec: CommandProcessSpec): RunningCommandProcess {
        val workingDirectory = File(spec.workingDirectory)
        require(workingDirectory.isDirectory) {
            "Command working directory does not exist or is not a directory: ${workingDirectory.absolutePath}"
        }
        val outputFile = commandOutputFile(spec)
        val processTreeFile = processTreeFile(outputFile)
        val processStartFile = processStartFile(outputFile)
        val exitCodeFile = exitCodeFile(outputFile)
        val errorFile = errorFile(outputFile).takeIf { spec.captureStandardErrorSeparately }
        var process: Process? = null
        var workerLifetimeBinding: LocalWorkerLifetimeBinding? = null
        try {
            process = host.launch(
                spec = spec,
                workingDirectory = workingDirectory,
                outputFile = outputFile,
                errorFile = errorFile,
                processTreeFile = processTreeFile,
                processStartFile = processStartFile,
                exitCodeFile = exitCodeFile,
            )
            val processTreeId = host.resolveProcessTreeId(process, processTreeFile)
            val processHandle = host.processTreeHandle(process, processTreeId)
            val startedAt = processHandle.info().startInstant().orElseThrow {
                IllegalStateException("OS did not expose start time for command process $processTreeId")
            }
            val processTree = host.processTree(processTreeId)
            workerLifetimeBinding = if (
                spec.lifetime == CommandTask.ProcessLifetime.WORKER_BOUND &&
                processHandle.isAlive
            ) {
                host.bindToWorker(processTreeId, outputFile)
            } else {
                null
            }
            host.releaseProcessStart(processStartFile)
            return LocalRunningCommandProcess(
                process = process,
                processHandle = processHandle,
                startedAt = startedAt.toKotlinInstant(),
                processTree = processTree,
                workerLifetimeBinding = workerLifetimeBinding,
                outputArtifact = outputFile,
                errorArtifact = errorFile,
                exitCodeArtifact = exitCodeFile,
            )
        } catch (error: Throwable) {
            runCatching { workerLifetimeBinding?.disarm() }.onFailure(error::addSuppressed)
            process?.toHandle()?.let { terminateStartupFailure(it, processTreeFile) }
            runCatching { deleteOutputArtifacts(outputFile.absolutePath) }
                .onFailure(error::addSuppressed)
            throw error
        }
    }

    override fun recover(spec: CommandProcessRecoverySpec): CommandProcessRecovery {
        val outputFile = runCatching { managedOutputFile(spec.outputFile) }
            .getOrElse { return CommandProcessRecovery.Unavailable(it.message ?: "Invalid command output artifact") }
        val exitCode = runCatching { readExitCode(outputFile) }
            .getOrElse { return CommandProcessRecovery.Unavailable(it.message ?: "Invalid command exit artifact") }
        if (exitCode != null) {
            return CommandProcessRecovery.Completed(exitCode)
        }
        val processId = spec.processId
            ?: return CommandProcessRecovery.Unavailable("Persisted command process id is missing")
        val processStartedAt = spec.processStartedAt
            ?: return CommandProcessRecovery.Unavailable("Persisted command process start time is missing")
        val processTreeId = spec.processTreeId
            ?.takeIf { it > 0 }
            ?: return CommandProcessRecovery.Unavailable("Persisted command process tree id is missing")
        val processHandle = ProcessHandle.of(processId).orElse(null)
            ?: return CommandProcessRecovery.Unavailable("Command process $processId is no longer running")
        val actualStartedAt = processHandle.info().startInstant().orElse(null)?.toKotlinInstant()
            ?: return CommandProcessRecovery.Unavailable("OS did not expose start time for command process $processId")
        if (actualStartedAt != processStartedAt) {
            return CommandProcessRecovery.Unavailable("Command process id $processId was reused by another process")
        }
        if (!processHandle.isAlive) {
            return readExitCode(outputFile)
                ?.let(CommandProcessRecovery::Completed)
                ?: CommandProcessRecovery.Unavailable("Command process $processId stopped without an exit artifact")
        }
        val processTree = runCatching { host.processTree(processTreeId) }
            .getOrElse {
                return CommandProcessRecovery.Unavailable(
                    it.message ?: "Command process tree $processTreeId cannot be recovered"
                )
            }
        val runningProcess = LocalRunningCommandProcess(
            process = null,
            processHandle = processHandle,
            startedAt = actualStartedAt,
            processTree = processTree,
            workerLifetimeBinding = null,
            outputArtifact = outputFile,
            errorArtifact = errorFile(outputFile).takeIf(File::isFile),
            exitCodeArtifact = exitCodeFile(outputFile),
        )
        return if (outputFile.isFile) {
            CommandProcessRecovery.Running(runningProcess)
        } else {
            CommandProcessRecovery.UnrecoverableRunning(
                process = runningProcess,
                reason = "Command output artifact is missing: ${outputFile.absolutePath}",
            )
        }
    }

    override fun deleteOutputArtifacts(outputFile: String) {
        val outputArtifact = managedOutputFile(outputFile)
        outputArtifacts(outputArtifact).forEach { artifact ->
            check(!artifact.exists() || artifact.delete()) {
                "Cannot delete command output artifact: ${artifact.absolutePath}"
            }
        }
    }

    override fun garbageCollectOutputArtifacts(
        spec: CommandOutputGarbageCollectionSpec,
    ): CommandOutputGarbageCollectionResult {
        val referenced = spec.referencedOutputFiles.mapNotNullTo(mutableSetOf()) { path ->
            runCatching { managedOutputFile(path).absolutePath }.getOrNull()
        }
        val protected = spec.protectedOutputFiles.mapTo(mutableSetOf()) { path ->
            managedOutputFile(path).absolutePath
        }
        val artifacts = outputDirectory().listFiles().orEmpty()
            .mapNotNull(::outputFileForArtifact)
            .distinctBy { it.absolutePath }
            .map(::outputArtifactGroup)
        val deleted = linkedSetOf<String>()
        val retained = artifacts.toMutableList()

        retained.removeAll { group ->
            val delete = group.outputFile.absolutePath !in referenced ||
                (
                    group.outputFile.absolutePath !in protected &&
                        group.lastModifiedAtMillis < spec.expireBefore.toEpochMilliseconds()
                )
            if (delete) {
                deleteOutputArtifacts(group.outputFile.absolutePath)
                deleted += group.outputFile.absolutePath
            }
            delete
        }

        var retainedBytes = retained.sumOf(OutputArtifactGroup::sizeBytes)
        val quotaCandidates = retained.asSequence()
            .filterNot { it.outputFile.absolutePath in protected }
            .sortedBy(OutputArtifactGroup::lastModifiedAtMillis)
        for (group in quotaCandidates) {
            if (retainedBytes <= spec.maxTotalBytes) break
            deleteOutputArtifacts(group.outputFile.absolutePath)
            deleted += group.outputFile.absolutePath
            retainedBytes -= group.sizeBytes
        }

        return CommandOutputGarbageCollectionResult(
            deletedOutputFiles = deleted,
            retainedBytes = retainedBytes,
            protectedBytes = retained
                .filter { it.outputFile.absolutePath in protected }
                .sumOf(OutputArtifactGroup::sizeBytes),
        )
    }

    private fun terminateStartupFailure(processHandle: ProcessHandle, processTreeFile: File) {
        sequenceOf(processTreeFile, File("${processTreeFile.absolutePath}.tmp"))
            .mapNotNull(::readPositiveLong)
            .firstOrNull()
            ?.let { processTreeId ->
                ProcessHandle.of(processTreeId).orElse(null)?.let { processTreeHandle ->
                    runCatching { host.processTree(processTreeId).terminate(processTreeHandle) }
                }
            }
        forceTerminateHandleTree(processHandle)
    }

    private fun commandOutputFile(spec: CommandProcessSpec): File = File(
        outputDirectory(),
        "command-${System.currentTimeMillis()}-${spec.command.sha256Prefix()}-${spec.executionId.take(8)}.log",
    ).apply {
        check(createNewFile()) { "Command output artifact already exists: $absolutePath" }
    }

    private fun outputDirectory(): File = File(gromozekaHome(), OUTPUT_DIRECTORY_NAME)
        .absoluteFile
        .normalize()
        .apply {
            check(isDirectory || mkdirs()) { "Cannot create command output directory: $absolutePath" }
        }

    private fun managedOutputFile(path: String): File {
        val outputDirectory = outputDirectory().canonicalFile
        val outputFile = File(path).canonicalFile
        require(outputFile.parentFile == outputDirectory && OUTPUT_FILE_NAME.matches(outputFile.name)) {
            "Command output artifact is outside the managed output directory: ${outputFile.absolutePath}"
        }
        return outputFile
    }

    private fun outputFileForArtifact(artifact: File): File? {
        val match = OUTPUT_ARTIFACT_NAME.matchEntire(artifact.name) ?: return null
        return File(artifact.parentFile, match.groupValues[1]).canonicalFile
    }

    private fun outputArtifacts(outputFile: File): List<File> = listOf(
        outputFile,
        processTreeFile(outputFile),
        File("${processTreeFile(outputFile).absolutePath}.tmp"),
        processStartFile(outputFile),
        exitCodeFile(outputFile),
        File("${exitCodeFile(outputFile).absolutePath}.tmp"),
        errorFile(outputFile),
        windowsCommandFile(outputFile),
        windowsWrapperFile(outputFile),
        windowsWatchdogFile(outputFile),
    )

    private fun outputArtifactGroup(outputFile: File): OutputArtifactGroup {
        val existingArtifacts = outputArtifacts(outputFile).filter(File::isFile)
        return OutputArtifactGroup(
            outputFile = outputFile,
            sizeBytes = existingArtifacts.sumOf(File::length),
            lastModifiedAtMillis = existingArtifacts.maxOfOrNull(File::lastModified) ?: 0L,
        )
    }

    private fun readExitCode(outputFile: File): Int? {
        val artifact = exitCodeFile(outputFile)
        if (!artifact.isFile) return null
        return artifact.readText().trim().toIntOrNull()
            ?: error("Command exit artifact is invalid: ${artifact.absolutePath}")
    }

    private fun gromozekaHome(): File {
        val configuredHome = System.getProperty("GROMOZEKA_HOME")
            ?: System.getenv("GROMOZEKA_HOME")
        return configuredHome
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?: File(System.getProperty("user.home"), ".gromozeka")
    }

    private fun String.sha256Prefix(): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(12)
    }

    internal class LocalRunningCommandProcess(
        private val process: Process?,
        private val processHandle: ProcessHandle,
        private val startedAt: Instant,
        private val processTree: LocalProcessTree,
        private val workerLifetimeBinding: LocalWorkerLifetimeBinding?,
        private val outputArtifact: File,
        private val errorArtifact: File?,
        private val exitCodeArtifact: File,
    ) : RunningCommandProcess {
        private val inputLock = Any()

        override val processId: Long
            get() = processHandle.pid()

        override val processStartedAt: Instant
            get() = startedAt

        override val processTreeId: Long
            get() = processTree.id

        override val outputFile: String
            get() = outputArtifact.absolutePath

        override val errorFile: String?
            get() = errorArtifact?.absolutePath

        override val acceptsInput: Boolean
            get() = process != null

        override fun isAlive(): Boolean = processHandle.isAlive

        override fun waitFor(timeoutMillis: Long): Boolean {
            require(timeoutMillis >= 0) { "Command wait timeout must be non-negative" }
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            while (processHandle.isAlive) {
                val remainingNanos = deadline - System.nanoTime()
                if (remainingNanos <= 0) return false
                TimeUnit.NANOSECONDS.sleep(minOf(remainingNanos, TimeUnit.MILLISECONDS.toNanos(TERMINATION_POLL_MILLIS)))
            }
            val stopped = !processHandle.isAlive
            if (stopped) {
                workerLifetimeBinding?.close()
            }
            return stopped
        }

        override fun exitCode(): Int {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(EXIT_ARTIFACT_WAIT_MILLIS)
            while (System.nanoTime() < deadline) {
                exitCodeArtifact.takeIf(File::isFile)
                    ?.readText()
                    ?.trim()
                    ?.toIntOrNull()
                    ?.let { return it }
                Thread.sleep(STARTUP_POLL_MILLIS)
            }
            process?.takeIf { !it.isAlive }?.let(Process::exitValue)?.let { return it }
            error("Exit code artifact is unavailable after reconnecting to process $processId")
        }

        override fun writeInput(bytes: ByteArray) {
            require(bytes.isNotEmpty()) { "Command process input must not be empty" }
            val directProcess = process
                ?: error("Cannot write input after reconnecting to command process $processId")
            synchronized(inputLock) {
                directProcess.outputStream.write(bytes)
                directProcess.outputStream.flush()
            }
        }

        override fun closeInput() {
            val directProcess = process
                ?: error("Cannot close input after reconnecting to command process $processId")
            synchronized(inputLock) {
                directProcess.outputStream.close()
            }
        }

        override fun terminateTree() {
            try {
                processTree.terminate(processHandle)
            } catch (error: Throwable) {
                runCatching { workerLifetimeBinding?.disarm() }.onFailure(error::addSuppressed)
                throw error
            }
            workerLifetimeBinding?.disarm()
        }
    }

    private data class OutputArtifactGroup(
        val outputFile: File,
        val sizeBytes: Long,
        val lastModifiedAtMillis: Long,
    )

    private companion object {
        const val OUTPUT_DIRECTORY_NAME = "tool-outputs"
        val OUTPUT_FILE_NAME = Regex("command-[A-Za-z0-9-]+\\.log")
        val OUTPUT_ARTIFACT_NAME = Regex(
            "(command-[A-Za-z0-9-]+\\.log)" +
                "(?:\\.(?:(?:tree|exit)(?:\\.tmp)?|start|stderr\\.log|(?:command|wrapper|watchdog)\\.cmd))?"
        )
    }
}

internal interface LocalCommandHost {
    fun launch(
        spec: CommandProcessSpec,
        workingDirectory: File,
        outputFile: File,
        errorFile: File?,
        processTreeFile: File,
        processStartFile: File,
        exitCodeFile: File,
    ): Process

    fun resolveProcessTreeId(process: Process, processTreeFile: File): Long

    fun processTreeHandle(process: Process, processTreeId: Long): ProcessHandle

    fun releaseProcessStart(processStartFile: File)

    fun bindToWorker(processTreeId: Long, outputFile: File): LocalWorkerLifetimeBinding

    fun processTree(id: Long): LocalProcessTree
}

internal interface LocalWorkerLifetimeBinding : AutoCloseable {
    fun disarm()
}

internal interface LocalProcessTree {
    val id: Long

    fun isAlive(processHandle: ProcessHandle): Boolean

    fun terminate(processHandle: ProcessHandle)
}

internal fun interface PosixProcessGroupInspector {
    fun processGroupId(processId: Long): Long?
}

internal fun interface PosixProcessGroupSignalSender {
    fun send(processGroupId: Long, signal: String): Boolean
}

internal fun currentLocalCommandHost(
    osName: String = System.getProperty("os.name"),
): LocalCommandHost {
    val normalized = osName.lowercase()
    return when {
        normalized.contains("windows") -> WindowsLocalCommandHost()
        normalized.contains("mac") -> PosixLocalCommandHost.forMacOS()
        normalized.contains("linux") -> PosixLocalCommandHost.forLinux()
        else -> error("Managed command execution is not supported on $osName")
    }
}

internal class PosixLocalCommandHost private constructor(
    private val launcherMode: String,
    private val launcherExecutable: String?,
    private val killExecutable: File,
    private val processGroupInspector: PosixProcessGroupInspector,
) : LocalCommandHost {
    override fun launch(
        spec: CommandProcessSpec,
        workingDirectory: File,
        outputFile: File,
        errorFile: File?,
        processTreeFile: File,
        processStartFile: File,
        exitCodeFile: File,
    ): Process = ProcessBuilder(
        POSIX_SHELL_PATH,
        "-c",
        POSIX_COMMAND_WRAPPER,
        "gromozeka-command",
        spec.command,
        processTreeFile.absolutePath,
        exitCodeFile.absolutePath,
        launcherMode,
        launcherExecutable.orEmpty(),
        POSIX_MANAGED_COMMAND,
        processStartFile.absolutePath,
    )
        .directory(workingDirectory)
        .redirectOutput(outputFile)
        .apply {
            environment().putAll(spec.environment)
            redirectErrorStream(errorFile == null)
            errorFile?.let(::redirectError)
        }
        .start()

    override fun resolveProcessTreeId(process: Process, processTreeFile: File): Long =
        awaitPositiveLong(processTreeFile, STARTUP_HANDSHAKE_MILLIS)

    override fun processTreeHandle(process: Process, processTreeId: Long): ProcessHandle =
        ProcessHandle.of(processTreeId).orElseThrow {
            IllegalStateException("Command process-tree root $processTreeId stopped during startup")
        }

    override fun releaseProcessStart(processStartFile: File) {
        processStartFile.writeText("start\n", StandardCharsets.UTF_8)
    }

    override fun bindToWorker(processTreeId: Long, outputFile: File): LocalWorkerLifetimeBinding {
        val processHandle = ProcessHandle.of(processTreeId).orElseThrow {
            IllegalStateException("Command process-tree root $processTreeId stopped before Worker binding")
        }
        processTree(processTreeId).requireSafeTarget(processHandle)
        val watchdog = when (launcherMode) {
            "job-control" -> ProcessBuilder(
                POSIX_SHELL_PATH,
                "-c",
                POSIX_WATCHDOG_JOB_CONTROL_WRAPPER,
                "gromozeka-watchdog-launcher",
                POSIX_WATCHDOG,
                killExecutable.absolutePath,
                processTreeId.toString(),
            )

            "setsid" -> ProcessBuilder(
                requireNotNull(launcherExecutable),
                "--wait",
                POSIX_SHELL_PATH,
                "-c",
                POSIX_WATCHDOG,
                "gromozeka-watchdog",
                killExecutable.absolutePath,
                processTreeId.toString(),
            )

            else -> error("Unsupported POSIX command launcher mode: $launcherMode")
        }
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        return ProcessWorkerLifetimeBinding(watchdog)
    }

    override fun processTree(id: Long): PosixProcessTree =
        PosixProcessTree(
            id = id,
            processGroupInspector = processGroupInspector,
            signalSender = PosixProcessGroupSignalSender { processGroupId, signal ->
                signalProcessGroup(killExecutable, processGroupId, signal)
            },
        )

    companion object {
        fun forMacOS(): PosixLocalCommandHost =
            PosixLocalCommandHost(
                launcherMode = "job-control",
                launcherExecutable = null,
                killExecutable = findExecutable(POSIX_KILL_EXECUTABLE_CANDIDATES, "POSIX kill"),
                processGroupInspector = commandProcessGroupInspector(),
            )

        fun forLinux(): PosixLocalCommandHost =
            PosixLocalCommandHost(
                launcherMode = "setsid",
                launcherExecutable = findExecutable(SETSID_EXECUTABLE_CANDIDATES, "setsid").absolutePath,
                killExecutable = findExecutable(POSIX_KILL_EXECUTABLE_CANDIDATES, "POSIX kill"),
                processGroupInspector = commandProcessGroupInspector(),
            )
    }
}

internal class WindowsLocalCommandHost(
    private val commandInterpreter: String = windowsCommandInterpreter(),
    private val taskkillExecutable: String = windowsTaskkillExecutable(),
) : LocalCommandHost {
    override fun launch(
        spec: CommandProcessSpec,
        workingDirectory: File,
        outputFile: File,
        errorFile: File?,
        processTreeFile: File,
        processStartFile: File,
        exitCodeFile: File,
    ): Process = prepareProcessBuilder(
        command = spec.command,
        workingDirectory = workingDirectory,
        outputFile = outputFile,
        errorFile = errorFile,
        exitCodeFile = exitCodeFile,
        injectedEnvironment = spec.environment,
    ).start()

    internal fun prepareProcessBuilder(
        command: String,
        workingDirectory: File,
        outputFile: File,
        errorFile: File? = null,
        exitCodeFile: File,
        injectedEnvironment: Map<String, String> = emptyMap(),
    ): ProcessBuilder {
        val commandFile = windowsCommandFile(outputFile)
        val wrapperFile = windowsWrapperFile(outputFile)
        commandFile.writeText(command.toWindowsCommandFile(), StandardCharsets.UTF_8)
        wrapperFile.writeText(WINDOWS_COMMAND_WRAPPER, StandardCharsets.UTF_8)
        return ProcessBuilder(
            commandInterpreter,
            "/D",
            "/Q",
            "/V:OFF",
            "/C",
            wrapperFile.absolutePath,
        )
            .directory(workingDirectory)
            .redirectOutput(outputFile)
            .apply {
                redirectErrorStream(errorFile == null)
                errorFile?.let(::redirectError)
                environment()[WINDOWS_COMMAND_FILE_ENV] = commandFile.absolutePath
                environment()[WINDOWS_EXIT_FILE_ENV] = exitCodeFile.absolutePath
                environment().putAll(injectedEnvironment)
            }
    }

    override fun resolveProcessTreeId(process: Process, processTreeFile: File): Long =
        process.pid().also { processTreeId ->
            writePositiveLongAtomically(processTreeFile, processTreeId)
        }

    override fun processTreeHandle(process: Process, processTreeId: Long): ProcessHandle =
        process.toHandle().also { processHandle ->
            check(processHandle.pid() == processTreeId) {
                "Windows command process tree $processTreeId does not match root process ${processHandle.pid()}"
            }
        }

    override fun releaseProcessStart(processStartFile: File) = Unit

    override fun bindToWorker(processTreeId: Long, outputFile: File): LocalWorkerLifetimeBinding {
        val watchdogFile = windowsWatchdogFile(outputFile)
        watchdogFile.writeText(WINDOWS_WATCHDOG, StandardCharsets.UTF_8)
        val watchdog = ProcessBuilder(
            commandInterpreter,
            "/D",
            "/Q",
            "/V:OFF",
            "/C",
            watchdogFile.absolutePath,
        )
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .apply {
                environment()[WINDOWS_PROCESS_TREE_ID_ENV] = processTreeId.toString()
                environment()[WINDOWS_TASKKILL_ENV] = taskkillExecutable
            }
            .start()
        return ProcessWorkerLifetimeBinding(watchdog)
    }

    override fun processTree(id: Long): LocalProcessTree =
        WindowsProcessTree(id, taskkillExecutable)
}

internal class PosixProcessTree(
    override val id: Long,
    private val processGroupInspector: PosixProcessGroupInspector,
    private val signalSender: PosixProcessGroupSignalSender,
) : LocalProcessTree {
    private val log = KLoggers.logger(this)

    override fun isAlive(processHandle: ProcessHandle): Boolean =
        processHandle.isAlive || processGroupIsAlive(signalSender, id)

    override fun terminate(processHandle: ProcessHandle) {
        requireSafeTarget(processHandle)
        signalSender.send(id, "TERM")
        waitUntilStopped(processHandle, TERMINATION_GRACE_MILLIS)
        if (processGroupIsAlive(signalSender, id)) {
            log.warn { "Command process group $id survived SIGTERM; sending SIGKILL" }
            signalSender.send(id, "KILL")
        }
        waitUntilStopped(processHandle, FORCE_TERMINATION_GRACE_MILLIS)
        if (processHandle.isAlive) {
            processHandle.destroyForcibly()
        }
        waitUntilStopped(processHandle, FORCE_TERMINATION_GRACE_MILLIS)
        check(!isAlive(processHandle)) {
            "Failed to terminate command process tree $id"
        }
    }

    internal fun requireSafeTarget(processHandle: ProcessHandle) {
        check(id > 1) { "Refusing to signal unsafe command process group $id" }
        check(processHandle.pid() == id) {
            "Refusing to signal command process group $id because it does not match command root ${processHandle.pid()}"
        }
        val groupLeader = processHandle
        check(groupLeader.isAlive) { "Command process group leader $id is no longer running" }
        val targetProcessGroupId = processGroupInspector.processGroupId(groupLeader.pid())
            ?: error("Cannot resolve process group for command leader ${groupLeader.pid()}")
        check(targetProcessGroupId == id) {
            "Refusing to signal command process group $id because leader ${groupLeader.pid()} belongs to group $targetProcessGroupId"
        }
        val currentProcessGroupId = processGroupInspector.processGroupId(ProcessHandle.current().pid())
            ?: error("Cannot resolve Worker process group")
        check(id != currentProcessGroupId) {
            "Refusing to signal Worker process group $id"
        }
        log.info {
            "Terminating command process group: " +
                "workerPid=${ProcessHandle.current().pid()} workerPgid=$currentProcessGroupId " +
                "commandPid=${groupLeader.pid()} commandPgid=$targetProcessGroupId"
        }
    }

    private fun waitUntilStopped(processHandle: ProcessHandle, timeoutMillis: Long) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (isAlive(processHandle) && System.nanoTime() < deadline) {
            Thread.sleep(TERMINATION_POLL_MILLIS)
        }
    }
}

private class WindowsProcessTree(
    override val id: Long,
    private val taskkillExecutable: String,
) : LocalProcessTree {
    override fun isAlive(processHandle: ProcessHandle): Boolean =
        processHandle.isAlive

    override fun terminate(processHandle: ProcessHandle) {
        require(processHandle.pid() == id) {
            "Windows command process tree $id does not match root process ${processHandle.pid()}"
        }
        val descendants = processHandle.descendants().toList()
        if (processHandle.isAlive) {
            runCatching { taskkill() }
        }
        waitUntilStopped(processHandle, descendants, FORCE_TERMINATION_GRACE_MILLIS)
        if (processHandle.isAlive || descendants.any(ProcessHandle::isAlive)) {
            descendants.asReversed().forEach { descendant ->
                if (descendant.isAlive) descendant.destroyForcibly()
            }
            if (processHandle.isAlive) processHandle.destroyForcibly()
        }
        waitUntilStopped(processHandle, descendants, FORCE_TERMINATION_GRACE_MILLIS)
        check(!processHandle.isAlive && descendants.none(ProcessHandle::isAlive)) {
            "Failed to terminate Windows command process tree $id"
        }
    }

    private fun taskkill(): Boolean =
        ProcessBuilder(taskkillExecutable, "/PID", id.toString(), "/T", "/F")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
            .let { process ->
                check(process.waitFor(TASKKILL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    "Timed out while terminating Windows command process tree $id"
                }
                process.exitValue() == 0
            }

    private fun waitUntilStopped(
        processHandle: ProcessHandle,
        descendants: List<ProcessHandle>,
        timeoutMillis: Long,
    ) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (
            (processHandle.isAlive || descendants.any(ProcessHandle::isAlive)) &&
            System.nanoTime() < deadline
        ) {
            Thread.sleep(TERMINATION_POLL_MILLIS)
        }
    }
}

private class ProcessWorkerLifetimeBinding(
    private val watchdog: Process,
) : LocalWorkerLifetimeBinding {
    private val closed = AtomicBoolean()

    init {
        try {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STARTUP_HANDSHAKE_MILLIS)
            while (watchdog.inputStream.available() < WATCHDOG_READY.length + 1) {
                check(watchdog.isAlive) { "Worker lifetime watchdog failed to start" }
                check(System.nanoTime() < deadline) { "Worker lifetime watchdog did not become ready" }
                Thread.sleep(STARTUP_POLL_MILLIS)
            }
            check(watchdog.inputStream.bufferedReader().readLine() == WATCHDOG_READY) {
                "Worker lifetime watchdog returned an invalid handshake"
            }
        } catch (error: Throwable) {
            runCatching { disarm() }.onFailure(error::addSuppressed)
            throw error
        }
    }

    override fun disarm() = finish(disarm = true)

    override fun close() = finish(disarm = false)

    private fun finish(disarm: Boolean) {
        if (!closed.compareAndSet(false, true)) return
        if (disarm) {
            watchdog.outputStream.write("disarm\n".toByteArray(StandardCharsets.US_ASCII))
            watchdog.outputStream.flush()
        }
        watchdog.outputStream.close()
        check(watchdog.waitFor(WATCHDOG_EXIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            watchdog.destroyForcibly()
            "Worker lifetime watchdog did not stop"
        }
    }
}

internal fun String.toWindowsCommandFile(): String {
    val normalized = replace("\r\n", "\n").replace('\r', '\n')
    val terminated = if (normalized.endsWith('\n')) normalized else "$normalized\n"
    return terminated.replace("\n", "\r\n")
}

private fun processTreeFile(outputFile: File): File = File("${outputFile.absolutePath}.tree")

private fun processStartFile(outputFile: File): File = File("${outputFile.absolutePath}.start")

private fun exitCodeFile(outputFile: File): File = File("${outputFile.absolutePath}.exit")

private fun errorFile(outputFile: File): File = File("${outputFile.absolutePath}.stderr.log")

private fun windowsCommandFile(outputFile: File): File = File("${outputFile.absolutePath}.command.cmd")

private fun windowsWrapperFile(outputFile: File): File = File("${outputFile.absolutePath}.wrapper.cmd")

private fun windowsWatchdogFile(outputFile: File): File = File("${outputFile.absolutePath}.watchdog.cmd")

private fun awaitPositiveLong(file: File, timeoutMillis: Long): Long {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
    while (System.nanoTime() < deadline) {
        readPositiveLong(file)?.let { return it }
        Thread.sleep(STARTUP_POLL_MILLIS)
    }
    error("Command process-tree handshake timed out: ${file.absolutePath}")
}

private fun readPositiveLong(file: File): Long? =
    file.takeIf(File::isFile)
        ?.readText()
        ?.trim()
        ?.toLongOrNull()
        ?.takeIf { it > 0 }

private fun writePositiveLongAtomically(file: File, value: Long) {
    require(value > 0) { "Command process tree id must be positive" }
    val temporaryFile = File("${file.absolutePath}.tmp")
    temporaryFile.writeText("$value\n", StandardCharsets.UTF_8)
    Files.move(
        temporaryFile.toPath(),
        file.toPath(),
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING,
    )
}

private fun findExecutable(candidates: List<String>, description: String): File =
    candidates
        .map(::File)
        .firstOrNull { it.isFile && it.canExecute() }
        ?: error("$description executable was not found")

private fun commandProcessGroupInspector(): PosixProcessGroupInspector {
    val psExecutable = findExecutable(POSIX_PS_EXECUTABLE_CANDIDATES, "POSIX ps")
    return PosixProcessGroupInspector { processId ->
        ProcessBuilder(
            psExecutable.absolutePath,
            "-o",
            "pgid=",
            "-p",
            processId.toString(),
        )
            .redirectErrorStream(true)
            .start()
            .let { process ->
                check(process.waitFor(PROCESS_INSPECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    "Timed out while resolving process group for process $processId"
                }
                if (process.exitValue() == 0) {
                    process.inputStream.bufferedReader().use { it.readText() }
                        .trim()
                        .toLongOrNull()
                        ?.takeIf { it > 0 }
                } else {
                    null
                }
            }
    }
}

private fun windowsCommandInterpreter(): String =
    System.getenv("ComSpec")
        ?.takeIf(String::isNotBlank)
        ?: "cmd.exe"

private fun windowsTaskkillExecutable(): String =
    System.getenv("SystemRoot")
        ?.takeIf(String::isNotBlank)
        ?.let { File(it, "System32/taskkill.exe") }
        ?.takeIf(File::isFile)
        ?.absolutePath
        ?: "taskkill.exe"

private fun signalProcessGroup(killExecutable: File, processTreeId: Long, signal: String): Boolean =
    ProcessBuilder(killExecutable.absolutePath, "-s", signal, "--", "-$processTreeId")
        .redirectErrorStream(true)
        .start()
        .let { process ->
            check(process.waitFor(TASKKILL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                "Timed out while sending SIG$signal to process group $processTreeId"
            }
            process.exitValue() == 0
        }

private fun processGroupIsAlive(signalSender: PosixProcessGroupSignalSender, processTreeId: Long): Boolean =
    signalSender.send(processTreeId, "0")

private fun forceTerminateHandleTree(processHandle: ProcessHandle) {
    val descendants = processHandle.descendants().toList()
    descendants.asReversed().forEach { descendant ->
        if (descendant.isAlive) descendant.destroyForcibly()
    }
    if (processHandle.isAlive) processHandle.destroyForcibly()
}

private fun java.time.Instant.toKotlinInstant(): Instant =
    Instant.fromEpochMilliseconds(toEpochMilli())

private const val POSIX_SHELL_PATH = "/bin/sh"
private const val STARTUP_HANDSHAKE_MILLIS = 2_000L
private const val STARTUP_POLL_MILLIS = 10L
private const val EXIT_ARTIFACT_WAIT_MILLIS = 2_000L
private const val TERMINATION_GRACE_MILLIS = 1_000L
private const val FORCE_TERMINATION_GRACE_MILLIS = 1_000L
private const val TERMINATION_POLL_MILLIS = 25L
private const val TASKKILL_TIMEOUT_SECONDS = 5L
private const val PROCESS_INSPECTION_TIMEOUT_SECONDS = 5L
private const val WATCHDOG_EXIT_TIMEOUT_SECONDS = 5L
private const val WATCHDOG_READY = "ready"
private const val WINDOWS_COMMAND_FILE_ENV = "GROMOZEKA_COMMAND_FILE"
private const val WINDOWS_EXIT_FILE_ENV = "GROMOZEKA_EXIT_FILE"
private const val WINDOWS_PROCESS_TREE_ID_ENV = "GROMOZEKA_PROCESS_TREE_ID"
private const val WINDOWS_TASKKILL_ENV = "GROMOZEKA_TASKKILL"
private val POSIX_KILL_EXECUTABLE_CANDIDATES = listOf("/bin/kill", "/usr/bin/kill")
private val POSIX_PS_EXECUTABLE_CANDIDATES = listOf("/bin/ps", "/usr/bin/ps")
private val SETSID_EXECUTABLE_CANDIDATES = listOf("/usr/bin/setsid", "/bin/setsid")
private val POSIX_COMMAND_WRAPPER = """
    case "${'$'}4" in
        job-control)
            set -m || exit 125
            /bin/sh -c "${'$'}6" gromozeka-command "${'$'}1" "${'$'}2" "${'$'}3" "${'$'}7" &
            command_pid=${'$'}!
            set +m
            wait "${'$'}command_pid" 2>/dev/null
            exit_code=${'$'}?
            ;;
        setsid)
            "${'$'}5" --wait /bin/sh -c "${'$'}6" gromozeka-command "${'$'}1" "${'$'}2" "${'$'}3" "${'$'}7"
            exit_code=${'$'}?
            ;;
        *)
            exit 125
            ;;
    esac
    exit "${'$'}exit_code"
""".trimIndent()
private val POSIX_MANAGED_COMMAND = """
    printf '%s\n' "${'$'}${'$'}" > "${'$'}2.tmp"
    /bin/mv "${'$'}2.tmp" "${'$'}2"
    remaining=500
    while [ "${'$'}remaining" -gt 0 ] && [ ! -f "${'$'}4" ]; do
        sleep 0.01
        remaining=${'$'}((remaining - 1))
    done
    [ -f "${'$'}4" ] || exit 125
    /bin/rm -f "${'$'}4"
    /bin/sh -c "${'$'}1"
    exit_code=${'$'}?
    printf '%s\n' "${'$'}exit_code" > "${'$'}3.tmp"
    /bin/mv "${'$'}3.tmp" "${'$'}3"
    exit "${'$'}exit_code"
""".trimIndent()
private val POSIX_WATCHDOG_JOB_CONTROL_WRAPPER = """
    set -m || exit 125
    /bin/sh -c "${'$'}1" gromozeka-watchdog "${'$'}2" "${'$'}3" <&0 &
    watchdog_pid=${'$'}!
    set +m
    wait "${'$'}watchdog_pid"
""".trimIndent()
private val POSIX_WATCHDOG = """
    terminate_tree() {
        trap - TERM HUP INT
        "${'$'}1" -s TERM -- "-${'$'}2" 2>/dev/null || return
        remaining=20
        while [ "${'$'}remaining" -gt 0 ] && "${'$'}1" -s 0 -- "-${'$'}2" 2>/dev/null; do
            sleep 0.05
            remaining=${'$'}((remaining - 1))
        done
        "${'$'}1" -s KILL -- "-${'$'}2" 2>/dev/null || true
    }
    trap 'terminate_tree "${'$'}1" "${'$'}2"; exit' TERM HUP INT
    printf '%s\n' '$WATCHDOG_READY'
    while IFS= read -r action; do
        [ "${'$'}action" = disarm ] && exit 0
    done
    terminate_tree "${'$'}1" "${'$'}2"
""".trimIndent()
private val WINDOWS_COMMAND_WRAPPER = """
    @echo off
    setlocal DisableDelayedExpansion
    chcp 65001 >NUL
    "%ComSpec%" /D /Q /V:OFF /C call "%GROMOZEKA_COMMAND_FILE%"
    set "GROMOZEKA_COMMAND_EXIT_CODE=%ERRORLEVEL%"
    > "%GROMOZEKA_EXIT_FILE%.tmp" echo %GROMOZEKA_COMMAND_EXIT_CODE%
    move /Y "%GROMOZEKA_EXIT_FILE%.tmp" "%GROMOZEKA_EXIT_FILE%" >NUL || exit /B 125
    del /Q "%GROMOZEKA_COMMAND_FILE%" >NUL 2>&1
    exit /B %GROMOZEKA_COMMAND_EXIT_CODE%
""".trimIndent().replace("\n", "\r\n") + "\r\n"
private val WINDOWS_WATCHDOG = """
    @echo off
    setlocal DisableDelayedExpansion
    echo $WATCHDOG_READY
    set "GROMOZEKA_WATCHDOG_ACTION="
    set /p "GROMOZEKA_WATCHDOG_ACTION="
    if "%GROMOZEKA_WATCHDOG_ACTION%"=="disarm" exit /B 0
    "%GROMOZEKA_TASKKILL%" /PID %GROMOZEKA_PROCESS_TREE_ID% /T /F >NUL 2>&1
""".trimIndent().replace("\n", "\r\n") + "\r\n"
