package com.gromozeka.bot.services

import klog.KLoggers
import java.util.concurrent.TimeUnit

class OllamaModelService {
    private val log = KLoggers.logger(this)

    data class ModelListResult(
        val models: List<String> = emptyList(),
        val error: String? = null
    ) {
        val isSuccess: Boolean get() = error == null
    }

    fun listModels(): ModelListResult {
        return try {
            log.debug { "Executing 'ollama list' command" }
            val process = ProcessBuilder("ollama", "list")
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().use { it.readText() }
            val finished = process.waitFor(10, TimeUnit.SECONDS)

            if (!finished) {
                process.destroyForcibly()
                log.warn { "Ollama list command timeout" }
                return ModelListResult(error = "Command timeout after 10 seconds")
            }

            if (process.exitValue() != 0) {
                log.warn { "Ollama list failed with exit code ${process.exitValue()}: $output" }
                return ModelListResult(error = "Ollama not available: ${output.take(200)}")
            }

            val models = parseOllamaListOutput(output)
            log.debug { "Found ${models.size} Ollama models: $models" }
            ModelListResult(models = models)

        } catch (e: Exception) {
            log.error(e) { "Failed to list Ollama models" }
            ModelListResult(error = "Error: ${e.message ?: e::class.simpleName}")
        }
    }

    private fun parseOllamaListOutput(output: String): List<String> {
        return output.lines()
            .drop(1) // Skip header line
            .mapNotNull { line ->
                line.trim()
                    .takeIf { it.isNotBlank() }
                    ?.split(Regex("\\s+"))
                    ?.firstOrNull()
                    ?.removeSuffix(":latest")
            }
            .distinct()
            .sorted()
    }
}
