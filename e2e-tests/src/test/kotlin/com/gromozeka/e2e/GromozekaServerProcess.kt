package com.gromozeka.e2e

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds

internal class GromozekaServerProcess private constructor(
    val remoteUrl: String,
    val bootstrapToken: String,
    private val process: Process,
    private val closing: AtomicBoolean,
) : AutoCloseable {
    override fun close() {
        closing.set(true)
        process.destroy()
        if (!process.waitFor(SERVER_STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor(SERVER_STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
    }

    companion object {
        fun start(database: PostgresTestDatabase, artifactsDirectory: Path): GromozekaServerProcess {
            Files.createDirectories(artifactsDirectory)
            val serverLog = artifactsDirectory.resolve("server.log")
            val serverJar = System.getProperty("gromozeka.e2e.serverJar")
                ?.let(Path::of)
                ?: error("gromozeka.e2e.serverJar is not configured")
            require(Files.isRegularFile(serverJar)) { "Server jar does not exist: $serverJar" }

            val javaExecutable = Path.of(
                System.getProperty("java.home"),
                "bin",
                if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java",
            )
            val process = ProcessBuilder(javaExecutable.toString(), "-jar", serverJar.toString())
                .redirectErrorStream(true)
                .apply {
                    environment()["GROMOZEKA_MODE"] = "e2e"
                    environment()["GROMOZEKA_REMOTE_HOST"] = "127.0.0.1"
                    environment()["GROMOZEKA_REMOTE_PORT"] = "0"
                    environment()["GROMOZEKA_AUTH_SECURE_COOKIE"] = "false"
                    environment()["GROMOZEKA_POSTGRES_JDBC_URL"] = database.jdbcUrl
                    environment()["GROMOZEKA_POSTGRES_USERNAME"] = database.username
                    environment()["GROMOZEKA_POSTGRES_PASSWORD"] = database.password
                    environment()["GROMOZEKA_LOG_DIR"] = artifactsDirectory.resolve("runtime-logs").toString()
                }
                .start()

            val output = ServerOutput()
            val closing = AtomicBoolean(false)
            thread(name = "gromozeka-e2e-server-output", isDaemon = true) {
                try {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            Files.writeString(
                                serverLog,
                                "$line\n",
                                StandardOpenOption.CREATE,
                                StandardOpenOption.APPEND,
                            )
                            output.accept(line)
                        }
                    }
                } catch (error: IOException) {
                    if (!closing.get()) throw error
                }
            }

            try {
                val started = output.awaitStarted(process, serverLog)
                return GromozekaServerProcess(
                    remoteUrl = started.remoteUrl,
                    bootstrapToken = started.bootstrapToken,
                    process = process,
                    closing = closing,
                )
            } catch (error: Throwable) {
                closing.set(true)
                process.destroyForcibly()
                throw error
            }
        }
    }
}

private class ServerOutput {
    private val lock = Object()
    private var remoteUrl: String? = null
    private var bootstrapToken: String? = null

    fun accept(line: String) {
        synchronized(lock) {
            remoteUrl = remoteUrl ?: SERVER_URL_REGEX.find(line)?.groupValues?.get(1)
            bootstrapToken = bootstrapToken ?: BOOTSTRAP_TOKEN_REGEX.find(line)?.groupValues?.get(1)
            lock.notifyAll()
        }
    }

    fun awaitStarted(process: Process, serverLog: Path): StartedServer {
        val deadlineNanos = System.nanoTime() + SERVER_START_TIMEOUT.inWholeNanoseconds
        synchronized(lock) {
            while (remoteUrl == null || bootstrapToken == null) {
                if (!process.isAlive) {
                    error("Gromozeka Server exited with code ${process.exitValue()}. Log: $serverLog")
                }
                val remainingNanos = deadlineNanos - System.nanoTime()
                if (remainingNanos <= 0) {
                    error("Timed out waiting for Gromozeka Server. Log: $serverLog")
                }
                TimeUnit.NANOSECONDS.timedWait(lock, remainingNanos.coerceAtMost(500_000_000))
            }
            return StartedServer(checkNotNull(remoteUrl), checkNotNull(bootstrapToken))
        }
    }
}

private data class StartedServer(
    val remoteUrl: String,
    val bootstrapToken: String,
)

private val SERVER_URL_REGEX = Regex("==== Gromozeka server started: (ws://[^ ]+/ws) ====")
private val BOOTSTRAP_TOKEN_REGEX = Regex("==== First-user bootstrap token: (.+) ====")
private val SERVER_START_TIMEOUT = 120.seconds
private const val SERVER_STOP_TIMEOUT_SECONDS = 10L
