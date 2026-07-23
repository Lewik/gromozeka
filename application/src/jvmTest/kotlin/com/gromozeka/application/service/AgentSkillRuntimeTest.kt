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
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
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
    fun `runtime exposes compact catalog and constrained activation tools`() = runBlocking {
        val repository = TestAgentSkillRepository(listOf(skillPackage))
        val service = AgentSkillRuntimeCatalogService(repository)
        val prepared = service.prepare(
            agent = agent(listOf(skill.id)),
            projectId = projectId,
            runtimeWorkerId = workerId,
            toolCatalog = toolCatalog(),
        )

        assertTrue(prepared.systemPrompt!!.contains("\"name\":\"release-check\""))
        assertFalse(prepared.systemPrompt.contains(skill.instructions))
        val activation = prepared.toolCatalog.tools
            .single { it.definition.name == ACTIVATE_AGENT_SKILL_TOOL_NAME }
        val nameSchema = Json.parseToJsonElement(activation.definition.inputSchema)
            .jsonObject
            .getValue("properties")
            .jsonObject
            .getValue("name")
            .jsonObject
        assertEquals(
            listOf("release-check"),
            nameSchema.getValue("enum").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `runtime hides skill tools when agent has no skills`() = runBlocking {
        val service = AgentSkillRuntimeCatalogService(TestAgentSkillRepository(listOf(skillPackage)))
        val prepared = service.prepare(
            agent = agent(emptyList()),
            projectId = projectId,
            runtimeWorkerId = workerId,
            toolCatalog = toolCatalog(),
        )

        assertEquals(null, prepared.systemPrompt)
        assertTrue(prepared.toolCatalog.tools.none { it.definition.name in skillToolNames })
        assertTrue(prepared.toolCatalog.entries.keys.none { it in skillToolNames })
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
                """{"name":"release-check","path":"references/unicode.txt","max_bytes":5}""",
                toolContext(assignedAgent),
            )
        ).jsonObject
        val second = Json.parseToJsonElement(
            callback.call(
                """{"name":"release-check","path":"references/unicode.txt","offset":3,"max_bytes":7}""",
                toolContext(assignedAgent),
            )
        ).jsonObject

        assertEquals("utf-8", first.getValue("encoding").jsonPrimitive.content)
        assertEquals("abc", first.getValue("content").jsonPrimitive.content)
        assertEquals(3, first.getValue("next_offset").jsonPrimitive.content.toInt())
        assertEquals("\uD83D\uDE80def", second.getValue("content").jsonPrimitive.content)
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
                executionScope = AiToolExecutionScope.CONVERSATION_RUNTIME,
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

        override suspend fun createWithPrompts(
            agent: AgentDefinition,
            prompts: List<Prompt>,
        ): AgentDefinition = error("Not used")

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
        )
    }
}
