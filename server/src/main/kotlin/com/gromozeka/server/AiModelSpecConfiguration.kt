package com.gromozeka.server

import com.gromozeka.domain.model.AiProvider
import com.gromozeka.domain.model.ai.AiModelSpec
import com.gromozeka.domain.repository.AiModelSpecRepository
import klog.KLoggers
import kotlinx.serialization.json.Json
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.io.File

@Configuration
class AiModelSpecConfiguration {
    private val log = KLoggers.logger(this)

    @Bean
    fun aiModelSpecRepository(): AiModelSpecRepository {
        val source = resolveSource()
        val specs = Json {
            ignoreUnknownKeys = true
            classDiscriminator = "type"
        }.decodeFromString<List<AiModelSpec>>(source.readText())

        log.info { "Loaded ${specs.size} AI model specs from ${source.description}" }

        return ResourceAiModelSpecRepository(specs)
    }

    private fun resolveSource(): ModelSpecSource {
        val configuredFile = System.getProperty("gromozeka.ai.model.specs.file")
            ?: System.getenv("GROMOZEKA_AI_MODEL_SPECS_FILE")

        if (configuredFile != null) {
            return ModelSpecSource.FileSource(File(configuredFile))
        }

        val resourceName = System.getProperty("gromozeka.ai.model.specs.resource")
            ?: System.getenv("GROMOZEKA_AI_MODEL_SPECS_RESOURCE")
            ?: "ai-model-specs.json"

        val classLoader = Thread.currentThread().contextClassLoader ?: javaClass.classLoader
        val resource = classLoader.getResource(resourceName)
            ?: error("AI model specs resource not found: $resourceName")

        return ModelSpecSource.ResourceSource(resourceName, resource.readText())
    }

    private sealed interface ModelSpecSource {
        val description: String
        fun readText(): String

        data class FileSource(private val file: File) : ModelSpecSource {
            override val description: String = file.absolutePath

            override fun readText(): String {
                require(file.isFile) { "AI model specs file does not exist: ${file.absolutePath}" }
                return file.readText()
            }
        }

        data class ResourceSource(
            private val resourceName: String,
            private val text: String,
        ) : ModelSpecSource {
            override val description: String = "classpath:$resourceName"

            override fun readText(): String = text
        }
    }

    private class ResourceAiModelSpecRepository(
        specs: List<AiModelSpec>,
    ) : AiModelSpecRepository {
        private val specs = specs.also { modelSpecs ->
            require(modelSpecs.map { it.provider to it.id }.distinct().size == modelSpecs.size) {
                "AI model specs must be unique by provider and model id"
            }
        }
        private val byProviderAndModel = specs.associateBy { it.provider to it.id }

        override suspend fun find(provider: AiProvider, modelId: String): AiModelSpec? =
            byProviderAndModel[provider to modelId]

        override suspend fun findAll(): List<AiModelSpec> = specs
    }
}
