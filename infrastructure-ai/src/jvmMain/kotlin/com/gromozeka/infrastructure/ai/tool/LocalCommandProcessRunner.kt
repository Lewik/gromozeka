package com.gromozeka.infrastructure.ai.tool

import com.gromozeka.domain.service.CommandProcessRunner
import com.gromozeka.domain.service.CommandProcessSpec
import com.gromozeka.domain.service.RunningCommandProcess
import kotlinx.datetime.Instant
import org.springframework.stereotype.Service
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

@Service
class LocalCommandProcessRunner : CommandProcessRunner {
    override fun start(spec: CommandProcessSpec): RunningCommandProcess {
        requireSupportedPlatform()
        val workingDirectory = File(spec.workingDirectory)
        require(workingDirectory.isDirectory) {
            "Command working directory does not exist or is not a directory: ${workingDirectory.absolutePath}"
        }
        val outputFile = commandOutputFile(spec)
        val process = ProcessBuilder("/bin/sh", "-c", spec.command)
            .directory(workingDirectory)
            .redirectErrorStream(true)
            .redirectOutput(outputFile)
            .start()
        val processHandle = process.toHandle()
        val startedAt = try {
            processHandle.info().startInstant().orElseThrow {
                IllegalStateException("OS did not expose start time for command process ${process.pid()}")
            }
        } catch (error: Throwable) {
            process.destroyForcibly()
            throw error
        }
        return LocalRunningCommandProcess(
            process = process,
            processHandle = processHandle,
            startedAt = startedAt.toKotlinInstant(),
            outputArtifact = outputFile,
        )
    }

    override fun reconnect(
        processId: Long,
        processStartedAt: Instant,
        outputFile: String,
    ): RunningCommandProcess? {
        requireSupportedPlatform()
        val processHandle = ProcessHandle.of(processId).orElse(null) ?: return null
        val actualStartedAt = processHandle.info().startInstant().orElse(null)?.toKotlinInstant() ?: return null
        if (actualStartedAt != processStartedAt) {
            return null
        }
        return LocalRunningCommandProcess(
            process = null,
            processHandle = processHandle,
            startedAt = actualStartedAt,
            outputArtifact = File(outputFile),
        )
    }

    private fun commandOutputFile(spec: CommandProcessSpec): File {
        val outputDirectory = File(gromozekaHome(), "tool-outputs")
        check(outputDirectory.isDirectory || outputDirectory.mkdirs()) {
            "Cannot create command output directory: ${outputDirectory.absolutePath}"
        }
        return File(
            outputDirectory,
            "command-${System.currentTimeMillis()}-${spec.command.sha256Prefix()}-${spec.taskId.value.take(8)}.log",
        ).apply {
            check(createNewFile()) { "Command output artifact already exists: $absolutePath" }
        }
    }

    private fun gromozekaHome(): File {
        val configuredHome = System.getProperty("GROMOZEKA_HOME")
            ?: System.getenv("GROMOZEKA_HOME")
        return configuredHome
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?: File(System.getProperty("user.home"), ".gromozeka")
    }

    private fun requireSupportedPlatform() {
        val osName = System.getProperty("os.name").lowercase()
        require(osName.contains("mac") || osName.contains("linux")) {
            "Managed command execution is supported only on macOS and Linux, current OS: $osName"
        }
    }

    private fun String.sha256Prefix(): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(12)
    }

    private class LocalRunningCommandProcess(
        private val process: Process?,
        private val processHandle: ProcessHandle,
        private val startedAt: Instant,
        private val outputArtifact: File,
    ) : RunningCommandProcess {
        override val processId: Long
            get() = processHandle.pid()

        override val processStartedAt: Instant
            get() = startedAt

        override val outputFile: String
            get() = outputArtifact.absolutePath

        override fun isAlive(): Boolean = processHandle.isAlive

        override fun waitFor(timeoutMillis: Long): Boolean = when {
            process != null -> process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
            !processHandle.isAlive -> true
            else -> {
                Thread.sleep(timeoutMillis)
                !processHandle.isAlive
            }
        }

        override fun exitCode(): Int = process?.exitValue()
            ?: error("Exit code is unavailable after reconnecting to process $processId")

        override fun terminateTree() {
            val descendants = processHandle.descendants().toList().asReversed()
            descendants.filter { it.isAlive }.forEach { it.destroy() }
            if (processHandle.isAlive) {
                processHandle.destroy()
            }
            waitUntilStopped(descendants + processHandle, TERMINATION_GRACE_MILLIS)
            val survivors = (descendants + processHandle).filter { it.isAlive }
            survivors.forEach { it.destroyForcibly() }
            waitUntilStopped(survivors, FORCE_TERMINATION_GRACE_MILLIS)
            val remaining = survivors.filter { it.isAlive }
            check(remaining.isEmpty()) {
                "Failed to terminate command process tree: ${remaining.joinToString { it.pid().toString() }}"
            }
        }

        private fun waitUntilStopped(handles: List<ProcessHandle>, timeoutMillis: Long) {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            while (handles.any { it.isAlive } && System.nanoTime() < deadline) {
                Thread.sleep(25)
            }
        }

        private companion object {
            const val TERMINATION_GRACE_MILLIS = 1_000L
            const val FORCE_TERMINATION_GRACE_MILLIS = 1_000L
        }
    }
}

private fun java.time.Instant.toKotlinInstant(): Instant = Instant.fromEpochMilliseconds(toEpochMilli())
