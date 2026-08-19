package com.gromozeka.infrastructure.ai.tool.skills

import com.gromozeka.domain.model.AgentSkill
import com.gromozeka.domain.model.AgentSkillFile
import com.gromozeka.domain.model.AgentSkillPackage
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.service.AgentSkillDirectoryImportClient
import com.gromozeka.domain.service.AgentSkillPackageRequest
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking

class AgentSkillDirectoryToolsTest {
    @Test
    fun `exports the exact package without replacing a directory`() = runBlocking {
        val root = createTempDirectory("gromozeka-skill-directory-export")
        try {
            val packageValue = packageValue()
            val service = LocalAgentSkillDirectoryService(
                packageClient = { packageValue },
                importClient = AgentSkillDirectoryImportClient { error("Not used") },
            )
            val destination = root.resolve("release-check")
            val request = AgentSkillPackageRequest(
                projectId = packageValue.skill.projectId,
                agentDefinitionId = com.gromozeka.domain.model.AgentDefinition.Id("agent-1"),
                skillId = packageValue.skill.id,
                contentHash = packageValue.skill.contentHash,
            )

            val result = service.export(request, destination.toString())

            assertEquals("Run the script.", destination.resolve("SKILL.md").readText())
            assertContentEquals(byteArrayOf(0, 1, 2), destination.resolve("assets/icon.bin").readBytes())
            assertEquals(2, result.fileCount)
            assertFailsWith<IllegalArgumentException> {
                service.export(request, destination.toString())
            }
            Unit
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `reads binary package files and rejects symbolic links`() {
        val root = createTempDirectory("gromozeka-skill-directory-import")
        try {
            val skillRoot = root.resolve("release-check").createDirectories()
            Files.writeString(skillRoot.resolve("SKILL.md"), "Run the script.")
            Files.createDirectories(skillRoot.resolve("assets"))
            Files.write(skillRoot.resolve("assets/icon.bin"), byteArrayOf(0, 1, 2))
            val service = LocalAgentSkillDirectoryService(
                packageClient = { error("Not used") },
                importClient = AgentSkillDirectoryImportClient { error("Not used") },
            )

            val source = service.readPackage(skillRoot.toString())

            assertEquals("release-check", source.directoryName)
            assertContentEquals(
                byteArrayOf(0, 1, 2),
                source.files.single { it.path == "assets/icon.bin" }.content,
            )

            val link = skillRoot.resolve("linked.bin")
            if (runCatching { Files.createSymbolicLink(link, skillRoot.resolve("assets/icon.bin")) }.isSuccess) {
                assertFailsWith<IllegalArgumentException> {
                    service.readPackage(skillRoot.toString())
                }
            }
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
                reason = "The package contains a binary.",
            ),
            contentHash = "a".repeat(64),
            createdAt = timestamp,
            updatedAt = timestamp,
        )
        return AgentSkillPackage(
            skill = skill,
            files = listOf(
                AgentSkillFile("SKILL.md", "Run the script.".encodeToByteArray()),
                AgentSkillFile("assets/icon.bin", byteArrayOf(0, 1, 2)),
            ),
        )
    }
}
