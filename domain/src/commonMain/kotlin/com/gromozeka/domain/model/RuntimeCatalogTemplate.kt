package com.gromozeka.domain.model

import com.gromozeka.domain.model.ai.AiCatalog
import com.gromozeka.domain.model.ai.AiRuntimeOverrides
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

@Serializable
data class PromptTemplate(
    val id: Id,
    val name: String,
    val content: String,
) {
    @Serializable
    @JvmInline
    value class Id(val value: String)
}

@Serializable
data class AgentTemplate(
    val id: Id,
    val name: String,
    val promptTemplateIds: List<PromptTemplate.Id>,
    val includeRuntimeEnvironment: Boolean = true,
    val runtimeSelection: AiRuntimeSelection,
    val runtimeOverrides: AiRuntimeOverrides = AiRuntimeOverrides(),
    val tools: List<String> = emptyList(),
    val description: String? = null,
) {
    @Serializable
    @JvmInline
    value class Id(val value: String)
}

@Serializable
data class RuntimeCatalogTemplates(
    val aiCatalog: AiCatalog,
    val prompts: List<PromptTemplate>,
    val agents: List<AgentTemplate>,
)

data class RuntimeCatalogSeed(
    val aiCatalog: AiCatalog,
    val prompts: List<Prompt>,
    val agents: List<AgentDefinition>,
)
