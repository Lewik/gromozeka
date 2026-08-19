package com.gromozeka.infrastructure.ai.tool.skills

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.AgentSkill
import com.gromozeka.domain.model.AgentSkillFile
import com.gromozeka.domain.model.AgentSkillPackage
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.WorkspaceMount
import com.gromozeka.domain.service.AgentSkillPackageRequest
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking

class AgentSkillMaterializationServiceTest {
    @Test
    fun `materializes the exact pinned package and reuses it`() = runBlocking {
        val root = createTempDirectory("gromozeka-skill-test")
        try {
            val skillPackage = packageValue()
            val service = LocalAgentSkillMaterializationService { skillPackage }
            val request = AgentSkillPackageRequest(
                projectId = skillPackage.skill.projectId,
                agentDefinitionId = AgentDefinition.Id("agent-1"),
                workspaceMountId = WorkspaceMount.Id("mount-1"),
                skillId = skillPackage.skill.id,
                contentHash = skillPackage.skill.contentHash,
            )

            val first = service.materialize(request, root.toString())
            val second = service.materialize(request, root.toString())

            assertFalse(first.alreadyPresent)
            assertTrue(second.alreadyPresent)
            assertEquals("echo ready", root.resolve(first.directoryPath).resolve("scripts/run.sh").readText())
            assertTrue(first.directoryPath.endsWith(skillPackage.skill.contentHash))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun packageValue(): AgentSkillPackage {
        val timestamp = Instant.parse("2026-08-19T10:00:00Z")
        val skill = AgentSkill(
            id = AgentSkill.Id("skill-1"),
            projectId = Project.Id("project-1"),
            name = "release-check",
            description = "Verify a release.",
            instructions = "Run the script.",
            materializationPlan = AgentSkill.MaterializationPlan(
                policy = AgentSkill.MaterializationPlan.Policy.REQUIRED,
                reason = "The package contains an executable script.",
            ),
            contentHash = "a".repeat(64),
            createdAt = timestamp,
            updatedAt = timestamp,
        )
        return AgentSkillPackage(
            skill = skill,
            files = listOf(
                AgentSkillFile("SKILL.md", "Run the script.".encodeToByteArray()),
                AgentSkillFile("scripts/run.sh", "echo ready".encodeToByteArray()),
            ),
        )
    }
}
