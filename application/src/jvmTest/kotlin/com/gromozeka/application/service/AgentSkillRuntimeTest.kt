package com.gromozeka.application.service

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.AgentSkill
import com.gromozeka.domain.model.AgentSkillFile
import com.gromozeka.domain.model.AgentSkillPackage
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.Prompt
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import com.gromozeka.domain.repository.AgentRepository
import com.gromozeka.domain.repository.AgentSkillRepository
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.tool.AiToolCallback
import com.gromozeka.domain.tool.AiToolDefinition
import com.gromozeka.domain.tool.AiToolDescriptor
import com.gromozeka.domain.tool.AiToolExecutionScope
import com.gromozeka.domain.tool.AiToolMetadata
import com.gromozeka.domain.tool.TOOL_CONTEXT_AGENT_DEFINITION_ID
import com.gromozeka.domain.tool.TOOL_CONTEXT_PROJECT_ID
import com.gromozeka.domain.tool.ToolExecutionContext
import com.gromozeka.domain.tool.skills.EXPORT_AGENT_SKILL_TO_DIRECTORY_TOOL_NAME
import com.gromozeka.domain.tool.skills.IMPORT_AGENT_SKILL_FROM_DIRECTORY_TOOL_NAME
import com.gromozeka.domain.tool.skills.MATERIALIZE_AGENT_SKILL_TOOL_NAME
import kotlinx.coroutines.runBlocking
import kotlin.time.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentSkillRuntimeTest {
    private val now = Clock.System.now()
    private val projectId = Project.Id("project-1")
    private val workerId = ConversationRuntimeWorkerId("worker-1")
    private val skill = AgentSkill(
        id = AgentSkill.Id("skill-1"),
        projectId = projectId,
        name = "release-check",
        description = "Verify a release.",
        instructions = "Follow every release check.",
        materializationPlan = AgentSkill.MaterializationPlan(
            policy = AgentSkill.MaterializationPlan.Policy.NOT_REQUIRED,
            reason = "Only model-readable references are used.",
            analyzedByModelConfigurationId = AiModelConfiguration.Id("model-1"),
            analyzedAt = now,
        ),
        contentHash = "a".repeat(64),
        createdAt = now,
        updatedAt = now,
    )
    private val skillPackage = AgentSkillPackage(
        skill = skill,
        files = listOf(
            AgentSkillFile("SKILL.md", "source".encodeToByteArray()),
            AgentSkillFile("references/checklist.md", "Check tags.".encodeToByteArray()),
        ),
    )

    @Test
    fun `runtime exposes compact catalog without mutating versioned tool contracts`() = runBlocking {
        val repository = TestAgentSkillRepository(listOf(skillPackage))
        val service = AgentSkillRuntimeCatalogService(repository)
        val originalCatalog = toolCatalog()
        val prepared = service.prepare(
            agent = agent(listOf(skill.id)),
            projectId = projectId,
            toolCatalog = originalCatalog,
        )

        assertTrue(prepared.systemPrompt!!.contains("\"name\":\"release-check\""))
        assertFalse(prepared.systemPrompt.contains(skill.instructions))
        assertTrue(prepared.systemPrompt.contains("Activation loads Skill instructions"))
        assertTrue(prepared.systemPrompt.contains("Use a catalog skill_id and content_hash directly"))
        assertTrue(prepared.systemPrompt.contains("Managed Skill materialization is effectively read-only"))
        assertTrue(prepared.systemPrompt.contains("To edit a Skill, export it with"))
        assertTrue(prepared.systemPrompt.contains("Do not edit or import a materialized runtime directory"))
        val activation = prepared.toolCatalog.tools
            .single { it.definition.name == ACTIVATE_AGENT_SKILL_TOOL_NAME }
        assertEquals(
            originalCatalog.tools.single { it.definition.name == ACTIVATE_AGENT_SKILL_TOOL_NAME }.definition,
            activation.definition,
        )
    }

    @Test
    fun `runtime keeps stable skill tools when agent has no skills`() = runBlocking {
        val service = AgentSkillRuntimeCatalogService(TestAgentSkillRepository(listOf(skillPackage)))
        val originalCatalog = toolCatalog()
        val prepared = service.prepare(
            agent = agent(emptyList()),
            projectId = projectId,
            toolCatalog = originalCatalog,
        )

        assertTrue(prepared.systemPrompt!!.contains("\"available_skills\":[]"))
        assertTrue(prepared.systemPrompt.contains("do not activate a Skill"))
        assertTrue(prepared.toolCatalog === originalCatalog)
    }

    @Test
    fun `runtime keeps assigned skill catalog when skill tools are unavailable`() = runBlocking {
        val service = AgentSkillRuntimeCatalogService(TestAgentSkillRepository(listOf(skillPackage)))
        val prepared = service.prepare(
            agent = agent(listOf(skill.id)),
            projectId = projectId,
            toolCatalog = toolCatalog().copy(tools = emptyList(), entries = emptyMap()),
        )

        assertTrue(prepared.systemPrompt!!.contains("\"name\":\"release-check\""))
        assertTrue(prepared.toolCatalog.tools.isEmpty())
    }

    @Test
    fun `activation returns only skills assigned to current agent`() {
        val assignedAgent = agent(listOf(skill.id))
        val repository = TestAgentSkillRepository(listOf(skillPackage))
        val access = AgentSkillRuntimeAccess(
            agentRepository = TestAgentRepository(assignedAgent),
            skillRepository = repository,
        )
        val callback = ActivateAgentSkillToolCallback(access)
        val result = callback.call(
            """{"name":"release-check"}""",
            toolContext(assignedAgent),
        )

        assertTrue(result.contains("\"instructions\":\"Follow every release check.\""))
        assertTrue(result.contains("\"path\":\"references/checklist.md\""))

        val unassignedAgent = agent(emptyList())
        val forbidden = ActivateAgentSkillToolCallback(
            AgentSkillRuntimeAccess(
                agentRepository = TestAgentRepository(unassignedAgent),
                skillRepository = repository,
            )
        )
        assertFailsWith<IllegalArgumentException> {
            forbidden.call("""{"name":"release-check"}""", toolContext(unassignedAgent))
        }
    }

    @Test
    fun `resource reader keeps utf8 chunks on code point boundaries`() {
        val text = "abc\uD83D\uDE80def"
        val textSkillPackage = skillPackage.copy(
            files = skillPackage.files + AgentSkillFile(
                "references/unicode.txt",
                text.encodeToByteArray(),
            )
        )
        val assignedAgent = agent(listOf(skill.id))
        val callback = ReadAgentSkillResourceToolCallback(
            AgentSkillRuntimeAccess(
                agentRepository = TestAgentRepository(assignedAgent),
                skillRepository = TestAgentSkillRepository(listOf(textSkillPackage)),
            )
        )

        val first = Json.parseToJsonElement(
            callback.call(
                """{"skill_id":"${skill.id.value}","content_hash":"${skill.contentHash}","path":"references/unicode.txt","max_bytes":5}""",
                toolContext(assignedAgent),
            )
        ).jsonObject
        val second = Json.parseToJsonElement(
            callback.call(
                """{"skill_id":"${skill.id.value}","content_hash":"${skill.contentHash}","path":"references/unicode.txt","offset":3,"max_bytes":7}""",
                toolContext(assignedAgent),
            )
        ).jsonObject

        assertEquals("utf-8", first.getValue("encoding").jsonPrimitive.content)
        assertEquals("abc", first.getValue("content").jsonPrimitive.content)
        assertEquals(3, first.getValue("next_offset").jsonPrimitive.content.toInt())
        assertEquals("\uD83D\uDE80def", second.getValue("content").jsonPrimitive.content)
    }

    @Test
    fun `resource reader rejects a stale package handle`() {
        val assignedAgent = agent(listOf(skill.id))
        val updatedSkill = skill.copy(contentHash = "b".repeat(64), updatedAt = now)
        val callback = ReadAgentSkillResourceToolCallback(
            AgentSkillRuntimeAccess(
                agentRepository = TestAgentRepository(assignedAgent),
                skillRepository = TestAgentSkillRepository(
                    listOf(skillPackage.copy(skill = updatedSkill))
                ),
            )
        )

        val error = assertFailsWith<IllegalArgumentException> {
            callback.call(
                """{"skill_id":"${skill.id.value}","content_hash":"${skill.contentHash}","path":"references/checklist.md"}""",
                toolContext(assignedAgent),
            )
        }

        assertTrue(error.message.orEmpty().contains("handle is stale"))
    }

    private fun agent(skills: List<AgentSkill.Id>): AgentDefinition =
        AgentDefinition(
            id = AgentDefinition.Id("agent-1"),
            projectId = projectId,
            name = "Agent",
            prompts = emptyList(),
            skills = skills,
            runtimeSelection = AiRuntimeSelection(AiModelConfiguration.Id("model-1")),
            type = AgentDefinition.Type.Project,
            createdAt = now,
            updatedAt = now,
        )

    private fun toolContext(agent: AgentDefinition): ToolExecutionContext =
        ToolExecutionContext(
            mapOf(
                TOOL_CONTEXT_PROJECT_ID to projectId.value,
                TOOL_CONTEXT_AGENT_DEFINITION_ID to agent.id.value,
            )
        )

    private fun toolCatalog(): DistributedAiToolCatalogSnapshot {
        val callbacks = skillToolNames.map(::toolCallback)
        val entries = callbacks.associate { callback ->
            callback.definition.name to DistributedAiTool(
                descriptor = AiToolDescriptor(callback.definition, callback.metadata),
                workers = listOf(
                    DistributedAiToolWorker(workerId, emptyList())
                ),
            )
        }
        return DistributedAiToolCatalogSnapshot(
            tools = callbacks,
            entries = entries,
            registrations = emptyList(),
            environmentRevision = "revision",
            environmentPrompt = "<execution_environment />",
        )
    }

    private fun toolCallback(name: String): AiToolCallback =
        object : AiToolCallback {
            override val definition = AiToolDefinition(
                name = name,
                description = name,
                inputSchema = """{"type":"object","properties":{}}""",
            )
            override val metadata = AiToolMetadata(
                executionScope = AiToolExecutionScope.SERVER,
            )

            override fun call(toolInput: String, context: ToolExecutionContext?): String =
                error("Not executed by catalog tests")
        }

    private class TestAgentSkillRepository(
        packages: List<AgentSkillPackage>,
    ) : AgentSkillRepository {
        private val packagesById = packages.associateBy { it.skill.id }.toMutableMap()

        override suspend fun savePackage(skillPackage: AgentSkillPackage): AgentSkillPackage {
            packagesById[skillPackage.skill.id] = skillPackage
            return skillPackage
        }

        override suspend fun findById(id: AgentSkill.Id): AgentSkill? = packagesById[id]?.skill

        override suspend fun findByIds(ids: List<AgentSkill.Id>): List<AgentSkill> =
            ids.mapNotNull { packagesById[it]?.skill }

        override suspend fun findByName(projectId: Project.Id, name: String): AgentSkill? =
            packagesById.values
                .map { it.skill }
                .singleOrNull { it.projectId == projectId && it.name == name }

        override suspend fun findByProject(projectId: Project.Id): List<AgentSkill> =
            packagesById.values.map { it.skill }.filter { it.projectId == projectId }

        override suspend fun findPackage(id: AgentSkill.Id): AgentSkillPackage? = packagesById[id]

        override suspend fun delete(id: AgentSkill.Id) {
            packagesById.remove(id)
        }
    }

    private class TestAgentRepository(
        private val agent: AgentDefinition,
    ) : AgentRepository {
        override suspend fun save(agent: AgentDefinition): AgentDefinition = error("Not used")

        override suspend fun findById(id: AgentDefinition.Id): AgentDefinition? =
            agent.takeIf { it.id == id }

        override suspend fun findAll(): List<AgentDefinition> = listOf(agent)

        override suspend fun findByProject(projectId: Project.Id): List<AgentDefinition> =
            listOf(agent).filter { it.projectId == projectId }

        override suspend fun delete(id: AgentDefinition.Id) = Unit

        override suspend fun count(): Int = 1
    }

    private companion object {
        val skillToolNames = setOf(
            ACTIVATE_AGENT_SKILL_TOOL_NAME,
            READ_AGENT_SKILL_RESOURCE_TOOL_NAME,
            MATERIALIZE_AGENT_SKILL_TOOL_NAME,
            EXPORT_AGENT_SKILL_TO_DIRECTORY_TOOL_NAME,
            IMPORT_AGENT_SKILL_FROM_DIRECTORY_TOOL_NAME,
        )
    }
}
