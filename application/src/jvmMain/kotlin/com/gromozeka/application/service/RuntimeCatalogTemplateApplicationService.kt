package com.gromozeka.application.service

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.AgentTemplate
import com.gromozeka.domain.model.Prompt
import com.gromozeka.domain.model.PromptTemplate
import com.gromozeka.domain.model.RuntimeCatalogSeed
import com.gromozeka.domain.model.RuntimeCatalogTemplates
import com.gromozeka.domain.model.RuntimeCatalogTemplateDefaults
import com.gromozeka.domain.model.ai.AiModelSpec
import com.gromozeka.domain.service.RuntimeCatalogTemplateService
import kotlinx.datetime.Clock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.stereotype.Service

@Service
class RuntimeCatalogTemplateApplicationService : RuntimeCatalogTemplateService {
    private val resourceResolver = PathMatchingResourcePatternResolver()
    private val json = Json {
        ignoreUnknownKeys = false
        classDiscriminator = "type"
    }
    private val loadedTemplates by lazy(::loadTemplates)

    override fun getTemplates(): RuntimeCatalogTemplates = loadedTemplates

    fun createSeed(): RuntimeCatalogSeed {
        val now = Clock.System.now()
        val templates = loadedTemplates
        val promptIds = templates.prompts.associate { template ->
            template.id to Prompt.Id("global:${template.id.value}")
        }
        val prompts = templates.prompts.map { template ->
            Prompt(
                id = promptIds.getValue(template.id),
                name = template.name,
                content = template.content,
                type = Prompt.Type.Global,
                createdAt = now,
                updatedAt = now,
            )
        }
        val agents = templates.agents.map { template ->
            val promptReferences = template.promptTemplateIds.map { templateId ->
                promptIds[templateId]
                    ?: error("Agent template ${template.id.value} references missing prompt template ${templateId.value}")
            } + if (template.includeRuntimeEnvironment) listOf(Prompt.Id(ENV_PROMPT_ID)) else emptyList()

            AgentDefinition(
                id = if (template.id.value == DEFAULT_AGENT_TEMPLATE_ID) {
                    templates.aiCatalog.defaultAgentId
                } else {
                    AgentDefinition.Id("global:${template.id.value}")
                },
                name = template.name,
                prompts = promptReferences,
                runtimeSelection = template.runtimeSelection,
                runtimeOverrides = template.runtimeOverrides,
                tools = template.tools,
                description = template.description,
                type = AgentDefinition.Type.Global,
                createdAt = now,
                updatedAt = now,
            )
        }
        check(agents.any { it.id == templates.aiCatalog.defaultAgentId }) {
            "Runtime catalog templates do not define default agent ${templates.aiCatalog.defaultAgentId.value}"
        }
        return RuntimeCatalogSeed(
            aiCatalog = templates.aiCatalog,
            prompts = prompts,
            agents = agents,
        )
    }

    private fun loadTemplates(): RuntimeCatalogTemplates {
        val promptTemplates = resourceResolver.getResources("classpath*:/prompts/*.md")
            .map { resource ->
                val fileName = checkNotNull(resource.filename) {
                    "Prompt template resource has no filename: $resource"
                }
                PromptTemplate(
                    id = PromptTemplate.Id(fileName),
                    name = fileName.removeSuffix(".md")
                        .replace("-", " ")
                        .replaceFirstChar { it.uppercase() },
                    content = resource.inputStream.bufferedReader().use { it.readText() },
                )
            }
            .sortedBy { it.name.lowercase() }
        check(promptTemplates.isNotEmpty()) { "No prompt templates found in classpath:/prompts" }
        check(promptTemplates.map { it.id }.distinct().size == promptTemplates.size) {
            "Prompt template ids must be unique"
        }

        val agentTemplates = resourceResolver.getResources("classpath*:/agents/*.json")
            .map { resource ->
                json.decodeFromString<AgentTemplate>(
                    resource.inputStream.bufferedReader().use { it.readText() }
                )
            }
            .sortedBy { it.name.lowercase() }
        check(agentTemplates.isNotEmpty()) { "No agent templates found in classpath:/agents" }
        check(agentTemplates.map { it.id }.distinct().size == agentTemplates.size) {
            "Agent template ids must be unique"
        }

        val modelSpecsResource = resourceResolver.getResource("classpath:/ai-model-specs.json")
        val modelSpecs = json.decodeFromString<List<AiModelSpec>>(
            modelSpecsResource.inputStream.bufferedReader().use { it.readText() }
        )
        val aiCatalog = RuntimeCatalogTemplateDefaults.catalog(modelSpecs)

        agentTemplates.forEach { template ->
            check(template.runtimeSelection.modelConfigurationId in aiCatalog.modelConfigurations.map { it.id }) {
                "Agent template ${template.id.value} references missing model configuration " +
                    template.runtimeSelection.modelConfigurationId.value
            }
        }

        return RuntimeCatalogTemplates(
            aiCatalog = aiCatalog,
            prompts = promptTemplates,
            agents = agentTemplates,
        )
    }

    private companion object {
        const val DEFAULT_AGENT_TEMPLATE_ID = "default-gromozeka"
        const val ENV_PROMPT_ID = "env"
    }
}
