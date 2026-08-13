package com.gromozeka.domain.model

import com.gromozeka.domain.model.ai.AiModelConfiguration
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

@Serializable
data class AgentSkill(
    val id: Id,
    val projectId: Project.Id,
    val name: String,
    val description: String,
    val instructions: String,
    val license: String? = null,
    val compatibility: String? = null,
    val metadata: Map<String, String> = emptyMap(),
    val allowedTools: String? = null,
    val materializationPlan: MaterializationPlan,
    val contentHash: String,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    @Serializable
    @JvmInline
    value class Id(val value: String)

    @Serializable
    data class MaterializationPlan(
        val policy: Policy,
        val reason: String,
        val analyzedByModelConfigurationId: AiModelConfiguration.Id? = null,
        val analyzedAt: Instant? = null,
    ) {
        init {
            require(reason.isNotBlank()) { "Agent Skill materialization reason must not be blank" }
            require((analyzedByModelConfigurationId == null) == (analyzedAt == null)) {
                "Agent Skill materialization analysis provenance must be complete"
            }
        }

        @Serializable
        enum class Policy {
            REQUIRED,
            NOT_REQUIRED,
        }
    }
}

@Serializable
data class AgentSkillFile(
    val path: String,
    val content: ByteArray,
)

@Serializable
data class AgentSkillPackage(
    val skill: AgentSkill,
    val files: List<AgentSkillFile>,
)

@Serializable
data class AgentSkillPackageSource(
    val directoryName: String,
    val files: List<AgentSkillFile>,
)
