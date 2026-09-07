package com.gromozeka.server

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.ProjectPermission
import com.gromozeka.domain.model.User
import com.gromozeka.domain.service.AgentDomainService
import com.gromozeka.domain.service.AgentSkillDomainService
import com.gromozeka.domain.service.ConversationDomainService
import com.gromozeka.domain.service.ProjectAccessService
import com.gromozeka.domain.service.PromptDomainService
import com.gromozeka.domain.service.UserDirectoryService
import com.gromozeka.domain.service.WorkspaceDomainService
import com.gromozeka.domain.tool.AiToolExecutionScope
import com.gromozeka.domain.tool.AiToolLoadingPolicy
import com.gromozeka.domain.tool.TOOL_CONTEXT_CONVERSATION_ID
import com.gromozeka.domain.tool.TOOL_CONTEXT_USER_ID
import com.gromozeka.domain.tool.ToolExecutionContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.mockito.Mockito

class ConversationToolContributorTest {
    private val user = testControlMcpCaller().user
    private val conversation = Conversation(
        id = Conversation.Id("current-conversation"),
        projectId = Project.Id("project"),
        participants = setOf(
            Conversation.Participant.User(user.id),
            Conversation.Participant.Agent(AgentDefinition.Id("agent")),
        ),
        currentThread = Conversation.Thread.Id("thread"),
        createdAt = Instant.fromEpochMilliseconds(1),
        updatedAt = Instant.fromEpochMilliseconds(1),
    )
    private val conversationService = mock<ConversationDomainService>()
    private val projectAccessService = mock<ProjectAccessService>()
    private val authorization = GromozekaRemoteAuthorization(
        projectAccessService = projectAccessService,
        conversationService = conversationService,
        agentService = mock<AgentDomainService>(),
        promptService = mock<PromptDomainService>(),
        skillService = mock<AgentSkillDomainService>(),
        workspaceService = mock<WorkspaceDomainService>(),
    )
    private val contributor = ConversationToolContributor(
        conversationService = conversationService,
        userDirectoryService = object : UserDirectoryService {
            override suspend fun findActiveById(id: User.Id): User? = user.takeIf { it.id == id }

            override suspend fun listActive(): List<User> = listOf(user)
        },
        authorization = authorization,
    )
    private val context = ToolExecutionContext(
        mapOf(
            TOOL_CONTEXT_CONVERSATION_ID to conversation.id.value,
            TOOL_CONTEXT_USER_ID to user.id.value,
        )
    )

    @Test
    fun `renames only the current authorized conversation`() = runBlocking {
        Mockito.`when`(conversationService.findById(conversation.id)).thenReturn(conversation)
        Mockito.`when`(conversationService.updateDisplayName(conversation.id, "Focused title"))
            .thenReturn(conversation.copy(displayName = "Focused title"))
        val tool = contributor.callbacks.single()

        val result = Json.parseToJsonElement(
            tool.call("""{"display_name":"  Focused title  "}""", context)
        ).jsonObject

        assertEquals("grz_conversation_rename", tool.definition.name)
        assertEquals(AiToolExecutionScope.SERVER, tool.metadata.executionScope)
        assertEquals(AiToolLoadingPolicy.PRELOAD_WHEN_AVAILABLE, tool.metadata.loadingPolicy)
        assertFalse(tool.metadata.visibleToMemoryPipeline)
        assertFalse(tool.definition.inputSchema.contains("conversation_id"))
        assertEquals(conversation.id.value, result.getValue("conversation_id").jsonPrimitive.content)
        assertEquals("Focused title", result.getValue("display_name").jsonPrimitive.content)
        Mockito.verify(projectAccessService)
            .requirePermission(user.id, conversation.projectId, ProjectPermission.WRITE)
        Mockito.verify(conversationService).updateDisplayName(conversation.id, "Focused title")
        Unit
    }

    @Test
    fun `rejects a caller-selected conversation`() {
        assertFailsWith<SerializationException> {
            contributor.callbacks.single().call(
                """{"conversation_id":"another","display_name":"Wrong target"}""",
                context,
            )
        }

        Mockito.verifyNoInteractions(projectAccessService)
    }

    @Test
    fun `requires conversation runtime context`() {
        assertFailsWith<IllegalStateException> {
            contributor.callbacks.single().call(
                """{"display_name":"Title"}""",
                ToolExecutionContext(mapOf(TOOL_CONTEXT_USER_ID to user.id.value)),
            )
        }

        Mockito.verifyNoInteractions(projectAccessService)
    }

    private inline fun <reified T> mock(): T = Mockito.mock(T::class.java)
}
