package com.gromozeka.server

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.Project
import com.gromozeka.domain.model.ProjectPermission
import com.gromozeka.domain.model.User
import com.gromozeka.domain.service.AgentDomainService
import com.gromozeka.domain.service.AgentSkillDomainService
import com.gromozeka.domain.service.ConversationDomainService
import com.gromozeka.domain.service.ProjectAccessDeniedException
import com.gromozeka.domain.service.ProjectAccessService
import com.gromozeka.domain.service.PromptDomainService
import com.gromozeka.domain.service.WorkspaceDomainService
import com.gromozeka.remote.protocol.FindConversationRequest
import com.gromozeka.remote.protocol.GetAiCatalogRequest
import com.gromozeka.remote.protocol.UpdateConversationDisplayNameRequest
import com.gromozeka.remote.protocol.UpdateProjectRequest
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import org.mockito.Mockito
import kotlin.test.Test
import kotlin.test.assertFailsWith

class GromozekaRemoteAuthorizationTest {
    private val projectAccessService = mock<ProjectAccessService>()
    private val conversationService = mock<ConversationDomainService>()
    private val authorization = GromozekaRemoteAuthorization(
        projectAccessService = projectAccessService,
        conversationService = conversationService,
        agentService = mock<AgentDomainService>(),
        promptService = mock<PromptDomainService>(),
        skillService = mock<AgentSkillDomainService>(),
        workspaceService = mock<WorkspaceDomainService>(),
    )

    @Test
    fun `global configuration requires server owner`() = runBlocking {
        assertFailsWith<ProjectAccessDeniedException> {
            authorization.authorize(testUser(User.Role.MEMBER), GetAiCatalogRequest)
        }

        authorization.authorize(testUser(User.Role.OWNER), GetAiCatalogRequest)
    }

    @Test
    fun `project mutation requires project write permission`() = runBlocking {
        val user = testUser()
        val projectId = Project.Id("project")

        authorization.authorize(
            user,
            UpdateProjectRequest(projectId, "Updated"),
        )

        Mockito.verify(projectAccessService).requirePermission(
            user.id,
            projectId,
            ProjectPermission.WRITE,
        )
    }

    @Test
    fun `conversation reads and writes use different permissions`() = runBlocking {
        val user = testUser()
        val conversation = testConversation()
        Mockito.`when`(conversationService.findById(conversation.id))
            .thenReturn(conversation)

        authorization.authorize(user, FindConversationRequest(conversation.id))
        authorization.authorize(
            user,
            UpdateConversationDisplayNameRequest(conversation.id, "Updated"),
        )

        Mockito.verify(projectAccessService).requirePermission(
            user.id,
            conversation.projectId,
            ProjectPermission.READ,
        )
        Mockito.verify(projectAccessService).requirePermission(
            user.id,
            conversation.projectId,
            ProjectPermission.WRITE,
        )
    }

    @Test
    fun `missing resource fails closed`() = runBlocking {
        val conversationId = Conversation.Id("missing")
        Mockito.`when`(conversationService.findById(conversationId))
            .thenReturn(null)

        assertFailsWith<ProjectAccessDeniedException> {
            authorization.authorize(testUser(), FindConversationRequest(conversationId))
        }
    }

    private inline fun <reified T> mock(): T = Mockito.mock(T::class.java)
}

private fun testUser(role: User.Role = User.Role.MEMBER): User =
    User(
        id = User.Id("remote-authorization-user"),
        username = "remote-authorization-user",
        displayName = "Remote Authorization User",
        status = User.Status.ACTIVE,
        role = role,
        createdAt = Instant.fromEpochMilliseconds(1),
        updatedAt = Instant.fromEpochMilliseconds(1),
    )

private fun testConversation(): Conversation =
    Conversation(
        id = Conversation.Id("conversation"),
        projectId = Project.Id("project"),
        agentDefinitionId = AgentDefinition.Id("agent"),
        currentThread = Conversation.Thread.Id("thread"),
        createdAt = Instant.fromEpochMilliseconds(1),
        updatedAt = Instant.fromEpochMilliseconds(1),
    )
