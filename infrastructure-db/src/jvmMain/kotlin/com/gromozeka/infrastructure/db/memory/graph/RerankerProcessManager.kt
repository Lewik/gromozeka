package com.gromozeka.infrastructure.db.memory.graph

import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import klog.KLoggers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

@Service
@ConditionalOnProperty(name = ["reranker.auto-start"], havingValue = "true", matchIfMissing = false)
class RerankerProcessManager(
    @Value("\${GROMOZEKA_HOME:\${user.home}/.gromozeka}") private val gromozekaHome: String,
    @Value("\${reranker.url:http://localhost:7997}") private val rerankerUrl: String,
    @Value("\${reranker.model:mixedbread-ai/mxbai-rerank-xsmall-v1}") private val modelId: String,
    @Value("\${reranker.startup-timeout-seconds:120}") private val startupTimeoutSeconds: Int,
    @Value("\${reranker.python-version:3.12}") private val pythonVersion: String
) {
    private val log = KLoggers.logger(this)
    private var process: Process? = null
    private val port: Int = URI(rerankerUrl).port.takeIf { it > 0 } ?: 7997

    @Volatile
    private var _isAvailable: Boolean = false
    val isAvailable: Boolean get() = _isAvailable

    @PostConstruct
    fun startIfNeeded() {
        if (isRerankerAvailable()) {
            log.info { "Reranker already available at $rerankerUrl" }
            _isAvailable = true
            return
        }

        log.info { "Reranker not available, starting process..." }
        try {
            startReranker()
            _isAvailable = true
        } catch (e: Exception) {
            log.error(e) { "Failed to start reranker: ${e.message}. Reranking will be disabled." }
            _isAvailable = false
        }
    }

    @PreDestroy
    fun stop() {
        process?.let { proc ->
            log.info { "Stopping reranker process..." }
            proc.descendants().forEach { it.destroy() }
            proc.destroy()
            proc.waitFor()
            log.info { "Reranker process stopped" }
        }
    }

    private fun startReranker() {
        val venvDir = File(gromozekaHome, "services/reranker-venv")
        val infinityPath = File(venvDir, "bin/infinity_emb")

        if (!infinityPath.exists()) {
            log.info { "Reranker venv not found, creating..." }
            createVenv(venvDir)
        }

        val processBuilder = ProcessBuilder(
            infinityPath.absolutePath,
            "v2",
            "--model-id", modelId,
            "--port", port.toString()
        ).apply {
            environment()["TOKENIZERS_PARALLELISM"] = "false"
            redirectErrorStream(true)
        }

        log.info { "Starting reranker: ${processBuilder.command().joinToString(" ")}" }
        process = processBuilder.start()

        Thread {
            process?.inputStream?.bufferedReader()?.forEachLine { line ->
                log.debug { "[reranker] $line" }
            }
        }.apply {
            isDaemon = true
            start()
        }

        waitForRerankerReady()
    }

    private fun createVenv(venvDir: File) {
        val servicesDir = venvDir.parentFile
        servicesDir.mkdirs()

        if (venvDir.exists()) {
            log.info { "Removing old venv..." }
            venvDir.deleteRecursively()
        }

        log.info { "Creating Python $pythonVersion venv at ${venvDir.absolutePath} using uv" }

        val venvProcess = ProcessBuilder("uv", "venv", "--python", pythonVersion, venvDir.absolutePath)
            .redirectErrorStream(true)
            .start()

        val venvOutput = venvProcess.inputStream.bufferedReader().readText()
        val venvResult = venvProcess.waitFor()

        if (venvResult != 0) {
            log.error { "uv venv output:\n$venvOutput" }
            throw RuntimeException("Failed to create venv with uv (exit code: $venvResult)")
        }

        val pythonPath = File(venvDir, "bin/python").absolutePath
        log.info { "Installing infinity-emb..." }

        val pipProcess = ProcessBuilder(
            "uv", "pip", "install", "--python", pythonPath,
            "infinity-emb[torch,server]==0.0.70",
            "typer==0.9.0",
            "click==8.1.7"
        )
            .redirectErrorStream(true)
            .start()

        val pipOutput = pipProcess.inputStream.bufferedReader().readText()
        val pipResult = pipProcess.waitFor()

        if (pipResult != 0) {
            log.error { "uv pip install output:\n$pipOutput" }
            throw RuntimeException("Failed to install infinity-emb (exit code: $pipResult)")
        }

        log.info { "infinity-emb installed successfully" }
    }

    private fun waitForRerankerReady() {
        log.info { "Waiting for reranker to become ready (timeout: ${startupTimeoutSeconds}s)..." }

        runBlocking {
            val startTime = System.currentTimeMillis()
            val timeoutMs = startupTimeoutSeconds * 1000L

            while (System.currentTimeMillis() - startTime < timeoutMs) {
                if (isRerankerAvailable()) {
                    log.info { "Reranker is ready at $rerankerUrl" }
                    return@runBlocking
                }

                if (process?.isAlive == false) {
                    throw RuntimeException("Reranker process died unexpectedly")
                }

                delay(1000)
            }

            throw RuntimeException("Reranker failed to start within ${startupTimeoutSeconds} seconds")
        }
    }

    private fun isRerankerAvailable(): Boolean {
        return try {
            val url = URI("$rerankerUrl/health").toURL()
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 2000
            connection.readTimeout = 2000
            connection.requestMethod = "GET"
            val responseCode = connection.responseCode
            connection.disconnect()
            responseCode == 200
        } catch (e: Exception) {
            false
        }
    }
}
