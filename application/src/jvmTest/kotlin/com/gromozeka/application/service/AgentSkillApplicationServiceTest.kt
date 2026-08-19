package com.gromozeka.application.service

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.AgentSkill
import com.gromozeka.domain.model.AgentSkillFile
import com.gromozeka.domain.model.AgentSkillPackage
import com.gromozeka.domain.model.AgentSkillPackageSource
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.User
import com.gromozeka.domain.repository.AgentRepository
import com.gromozeka.domain.repository.AgentSkillRepository
import com.gromozeka.domain.repository.ProjectRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking

class AgentSkillApplicationServiceTest {
    @Test
    fun `manual materialization override survives an unchanged reimport`() = runBlocking {
        val source = skillSource()
        val service = service(
            analyzer = AgentSkillMaterializationPlanAnalyzer {
                _, _ -> analyzedPlan(AgentSkill.MaterializationPlan.Policy.NOT_REQUIRED)
            },
        )
        val imported = service.importPackage(PROJECT_ID, source)

        val overridden = service.setMaterializationPlan(
            id = imported.id,
            policy = AgentSkill.MaterializationPlan.Policy.REQUIRED,
            reason = "Operator requires workspace files.",
        )
        val reimported = service.importPackage(PROJECT_ID, source)

        assertEquals(AgentSkill.MaterializationPlan.Policy.REQUIRED, overridden.materializationPlan.policy)
        assertNull(overridden.materializationPlan.analyzedAt)
        assertEquals(overridden, reimported)
    }

    @Test
    fun `reanalyze replaces a manual materialization override`() = runBlocking {
        var analyses = 0
        val service = service(
            analyzer = AgentSkillMaterializationPlanAnalyzer { _, _ ->
                analyses += 1
                analyzedPlan(AgentSkill.MaterializationPlan.Policy.NOT_REQUIRED)
            },
        )
        val imported = service.importPackage(PROJECT_ID, skillSource())
        service.setMaterializationPlan(
            id = imported.id,
            policy = AgentSkill.MaterializationPlan.Policy.REQUIRED,
            reason = "Operator override.",
        )

        val reanalyzed = service.reanalyzeMaterialization(imported.id)

        assertEquals(2, analyses)
        assertEquals(AgentSkill.MaterializationPlan.Policy.NOT_REQUIRED, reanalyzed.materializationPlan.policy)
        assertEquals(MODEL_ID, reanalyzed.materializationPlan.analyzedByModelConfigurationId)
    }

    @Test
    fun `directory import requires the current hash when updating`() = runBlocking {
        val service = service(
            analyzer = AgentSkillMaterializationPlanAnalyzer {
                _, _ -> analyzedPlan(AgentSkill.MaterializationPlan.Policy.NOT_REQUIRED)
            },
        )
        val created = service.importDirectoryPackage(
            projectId = PROJECT_ID,
            source = skillSource(),
            expectedContentHash = null,
            actorUserId = USER_ID,
        )
        val changedSource = skillSource("Follow the updated release checklist.")

        assertFailsWith<IllegalArgumentException> {
            service.importDirectoryPackage(PROJECT_ID, changedSource, null, USER_ID)
        }
        assertFailsWith<IllegalArgumentException> {
            service.importDirectoryPackage(PROJECT_ID, changedSource, "b".repeat(64), USER_ID)
        }

        val updated = service.importDirectoryPackage(
            PROJECT_ID,
            changedSource,
            created.contentHash,
            USER_ID,
        )

        assertEquals(created.id, updated.id)
        assertEquals("Follow the updated release checklist.", updated.instructions)
    }

    @Test
    fun `directory import rejects an expected hash for a new skill`() = runBlocking {
        val service = service(
            analyzer = AgentSkillMaterializationPlanAnalyzer {
                _, _ -> analyzedPlan(AgentSkill.MaterializationPlan.Policy.NOT_REQUIRED)
            },
        )

        assertFailsWith<IllegalArgumentException> {
            service.importDirectoryPackage(
                PROJECT_ID,
                skillSource(),
                "a".repeat(64),
                USER_ID,
            )
        }
        Unit
    }

    private fun service(
        analyzer: AgentSkillMaterializationPlanAnalyzer,
    ) = AgentSkillApplicationService(
        skillRepository = InMemoryAgentSkillRepository(),
        agentRepository = EmptyAgentRepository,
        projectRepository = ExistingProjectRepository,
        materializationPlanAnalyzer = analyzer,
    )

    private fun skillSource(
        instructions: String = "Follow the release checklist.",
    ) = AgentSkillPackageSource(
        directoryName = "release-check",
        files = listOf(
            AgentSkillFile(
                path = "SKILL.md",
                content = """
                    ---
                    name: release-check
                    description: Verify releases.
                    ---
                    $instructions
                """.trimIndent().encodeToByteArray(),
            ),
        ),
    )

    private fun analyzedPlan(policy: AgentSkill.MaterializationPlan.Policy) =
        AgentSkill.MaterializationPlan(
            policy = policy,
            reason = "Analyzed package.",
            analyzedByModelConfigurationId = MODEL_ID,
            analyzedAt = ANALYZED_AT,
        )

    private class InMemoryAgentSkillRepository : AgentSkillRepository {
        private val packages = mutableMapOf<AgentSkill.Id, AgentSkillPackage>()

        override suspend fun savePackage(skillPackage: AgentSkillPackage): AgentSkillPackage =
            skillPackage.also { packages[it.skill.id] = it }

        override suspend fun findById(id: AgentSkill.Id): AgentSkill? = packages[id]?.skill

        override suspend fun findByIds(ids: List<AgentSkill.Id>): List<AgentSkill> =
            ids.mapNotNull { packages[it]?.skill }

        override suspend fun findByName(projectId: Project.Id, name: String): AgentSkill? =
            packages.values.singleOrNull { it.skill.projectId == projectId && it.skill.name == name }?.skill

        override suspend fun findByProject(projectId: Project.Id): List<AgentSkill> =
            packages.values.map(AgentSkillPackage::skill).filter { it.projectId == projectId }

        override suspend fun findPackage(id: AgentSkill.Id): AgentSkillPackage? = packages[id]

        override suspend fun delete(id: AgentSkill.Id) {
            packages.remove(id)
        }
    }

    private object ExistingProjectRepository : ProjectRepository {
        override suspend fun save(project: Project): Project = project
        override suspend fun findById(id: Project.Id): Project? = null
        override suspend fun findAll(): List<Project> = emptyList()
        override suspend fun findRecent(limit: Int): List<Project> = emptyList()
        override suspend fun delete(id: Project.Id) = Unit
        override suspend fun exists(id: Project.Id): Boolean = id == PROJECT_ID
    }

    private object EmptyAgentRepository : AgentRepository {
        override suspend fun save(agent: AgentDefinition): AgentDefinition = agent
        override suspend fun findById(id: AgentDefinition.Id): AgentDefinition? = null
        override suspend fun findAll(): List<AgentDefinition> = emptyList()
        override suspend fun findByProject(projectId: Project.Id): List<AgentDefinition> = emptyList()
        override suspend fun delete(id: AgentDefinition.Id) = Unit
        override suspend fun count(): Int = 0
    }

    private companion object {
        val PROJECT_ID = Project.Id("project-1")
        val USER_ID = User.Id("user-1")
        val MODEL_ID = com.gromozeka.domain.model.ai.AiModelConfiguration.Id("analysis-model")
        val ANALYZED_AT = Instant.parse("2026-08-19T10:00:00Z")
    }
}
