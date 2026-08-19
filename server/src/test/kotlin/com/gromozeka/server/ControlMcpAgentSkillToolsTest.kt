package com.gromozeka.server

import com.gromozeka.domain.model.AgentSkill
import com.gromozeka.domain.model.AgentSkillFile
import com.gromozeka.domain.model.AgentSkillPackage
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.service.AgentDomainService
import com.gromozeka.domain.service.AgentSkillDomainService
import com.gromozeka.domain.service.ProjectAccessService
import com.gromozeka.domain.service.PromptDomainService
import com.gromozeka.domain.service.RuntimeCatalogTemplateService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.mockito.Mockito

class ControlMcpAgentSkillToolsTest {
    @Test
    fun `skill list is compact and cursor paginated`() = runBlocking {
        val projectId = Project.Id("project-1")
        val skills = listOf(skill("skill-1", "Alpha", "first instructions"), skill("skill-2", "Beta", "second instructions"))
        val service = mock<AgentSkillDomainService>()
        Mockito.`when`(service.findByProject(projectId)).thenReturn(skills)
        val tool = provider(service).tools.single { it.definition.name == "grz_skill_list" }

        val first = tool.invokeStructured(
            testControlMcpContext(),
            buildJsonObject {
                put("projectId", projectId.value)
                put("limit", 1)
            },
        ).getValue("result").jsonObject
        val cursor = first.getValue("nextCursor").jsonPrimitive.content
        val firstSkill = first.getValue("skills").jsonArray.single().jsonObject
        val second = tool.invokeStructured(
            testControlMcpContext(),
            buildJsonObject {
                put("projectId", projectId.value)
                put("limit", 1)
                put("cursor", cursor)
            },
        ).getValue("result").jsonObject

        assertEquals("Alpha", firstSkill.getValue("name").jsonPrimitive.content)
        assertFalse(firstSkill.containsKey("instructions"))
        assertTrue(first.getValue("hasMore").jsonPrimitive.content.toBoolean())
        assertEquals("Beta", second.getValue("skills").jsonArray.single().jsonObject.getValue("name").jsonPrimitive.content)
        assertFalse(second.getValue("hasMore").jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `skill get returns instructions and manifest without file contents`() = runBlocking {
        val skill = skill("skill-1", "Release", "Follow checks.")
        val packageValue = AgentSkillPackage(
            skill,
            listOf(
                AgentSkillFile("SKILL.md", "Follow checks.".encodeToByteArray()),
                AgentSkillFile("assets/icon.bin", byteArrayOf(0, 1, 2)),
            ),
        )
        val service = mock<AgentSkillDomainService>()
        Mockito.`when`(service.findById(skill.id)).thenReturn(skill)
        Mockito.`when`(service.exportPackage(skill.id)).thenReturn(packageValue)
        val tool = provider(service).tools.single { it.definition.name == "grz_skill_get" }

        val result = tool.invokeStructured(
            testControlMcpContext(),
            buildJsonObject { put("skillId", skill.id.value) },
        ).getValue("result").jsonObject

        assertEquals("Follow checks.", result.getValue("skill").jsonObject.getValue("instructions").jsonPrimitive.content)
        val manifest = result.getValue("files").jsonArray
        assertEquals(listOf("SKILL.md", "assets/icon.bin"), manifest.map { it.jsonObject.getValue("path").jsonPrimitive.content })
        assertFalse(manifest.toString().contains("Follow checks."))
        assertFalse(manifest.toString().contains("content"))
    }

    @Test
    fun `inline import schema defines canonical files and replacement semantics`() {
        val tool = provider(mock<AgentSkillDomainService>()).tools
            .single { it.definition.name == "grz_skill_import_inline" }
        val files = checkNotNull(tool.definition.inputSchema.properties)
            .getValue("files")
            .jsonObject
        val items = files.getValue("items").jsonObject

        assertTrue(tool.definition.description.orEmpty().contains("replaces the entire previous package"))
        assertEquals(
            setOf("path", "encoding", "content"),
            items.getValue("properties").jsonObject.keys,
        )
        assertEquals(
            listOf("path", "encoding", "content"),
            items.getValue("required").jsonArray.map { it.jsonPrimitive.content },
        )
        assertFalse(items.getValue("additionalProperties").jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `skill export uses one explicit encoding per file`() = runBlocking {
        val skill = skill("skill-1", "Release", "Follow checks.")
        val packageValue = AgentSkillPackage(
            skill,
            listOf(
                AgentSkillFile("SKILL.md", "Follow checks.".encodeToByteArray()),
                AgentSkillFile("assets/icon.bin", byteArrayOf(0, 1, 2)),
            ),
        )
        val service = mock<AgentSkillDomainService>()
        Mockito.`when`(service.findById(skill.id)).thenReturn(skill)
        Mockito.`when`(service.exportPackage(skill.id)).thenReturn(packageValue)
        val tool = provider(service).tools.single { it.definition.name == "grz_skill_export" }

        val result = tool.invokeStructured(
            testControlMcpContext(),
            buildJsonObject { put("skillId", skill.id.value) },
        ).getValue("result").jsonObject
        val files = result.getValue("files").jsonArray.map { it.jsonObject }

        assertFalse(result.getValue("skill").jsonObject.containsKey("instructions"))
        assertEquals("utf-8", files[0].getValue("encoding").jsonPrimitive.content)
        assertEquals("Follow checks.", files[0].getValue("content").jsonPrimitive.content)
        assertEquals("base64", files[1].getValue("encoding").jsonPrimitive.content)
        assertEquals("AAEC", files[1].getValue("content").jsonPrimitive.content)
        assertTrue(files.all { it.keys == setOf("path", "encoding", "content") })
    }

    private fun provider(skillService: AgentSkillDomainService) = ControlMcpAgentCatalogTools(
        agentService = mock<AgentDomainService>(),
        promptService = mock<PromptDomainService>(),
        skillService = skillService,
        templateService = mock<RuntimeCatalogTemplateService>(),
        projectAccessService = mock<ProjectAccessService>(),
    )

    private fun skill(id: String, name: String, instructions: String): AgentSkill {
        val timestamp = Instant.parse("2026-08-19T10:00:00Z")
        return AgentSkill(
            id = AgentSkill.Id(id),
            projectId = Project.Id("project-1"),
            name = name,
            description = "$name skill",
            instructions = instructions,
            materializationPlan = AgentSkill.MaterializationPlan(
                policy = AgentSkill.MaterializationPlan.Policy.NOT_REQUIRED,
                reason = "Text-only package.",
            ),
            contentHash = id.last().toString().repeat(64),
            createdAt = timestamp,
            updatedAt = timestamp,
        )
    }

    private inline fun <reified T> mock(): T = Mockito.mock(T::class.java)
}
