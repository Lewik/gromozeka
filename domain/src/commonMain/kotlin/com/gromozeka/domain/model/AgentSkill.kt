package com.gromozeka.domain.model

import kotlinx.datetime.Instant
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
    val contentHash: String,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    @Serializable
    @JvmInline
    value class Id(val value: String)
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
