package com.gromozeka.server

import com.gromozeka.application.service.AutoApproveToolApprovalService
import com.gromozeka.application.service.ParallelToolExecutor
import com.gromozeka.application.service.ToolInvocationContextResolver
import com.gromozeka.domain.model.*
import com.gromozeka.domain.repository.ConversationRepository
import com.gromozeka.domain.service.*
import com.gromozeka.domain.tool.*
import com.gromozeka.domain.tool.AiToolContextField.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.mockito.Mockito
import kotlin.test.*
import kotlin.time.Instant

class NativeToolContextContractTest {
    @Test fun nativeRenameRunsThroughResolverAndExecutorWithOnlySelectedConversation() = runBlocking<Unit> {
        val now = Instant.parse("2026-01-01T00:00:00Z")
        // Human rename does not acquire an AI permission requirement from the context refactoring.
        val user = User(User.Id("context-test-user"), displayName = "Synthetic", status = User.Status.ACTIVE,
            createdAt = now, updatedAt = now, aiAllowed = false)
        val conversation = Conversation(Conversation.Id("context-test-conversation"), Project.Id("context-test-project"),
            setOf(Conversation.Participant.User(user.id)), currentThread = Conversation.Thread.Id("context-test-thread"),
            createdAt = now, updatedAt = now)
        val users = Mockito.mock(UserDirectoryService::class.java)
        val conversations = Mockito.mock(ConversationRepository::class.java)
        val service = Mockito.mock(ConversationDomainService::class.java)
        val authorization = Mockito.mock(GromozekaRemoteAuthorization::class.java)
        val projects = Mockito.mock(ProjectAccessService::class.java)
        val workspaces = Mockito.mock(WorkspaceDomainService::class.java)
        val workers = Mockito.mock(WorkerAccessService::class.java)
        val agents = Mockito.mock(AgentDomainService::class.java)
        val coordinator = Mockito.mock(ConversationRuntimeCoordinator::class.java)
        Mockito.`when`(users.findActiveById(user.id)).thenReturn(user)
        Mockito.`when`(conversations.findById(conversation.id)).thenReturn(conversation)
        Mockito.`when`(service.updateDisplayName(conversation.id, "New title")).thenReturn(conversation.copy(displayName = "New title"))
        val callback = ConversationToolContributor(service, users, authorization).callbacks.single()
        assertEquals(setOf(USER, CONVERSATION), callback.metadata.contextRequirements.required)
        val descriptor = AiToolDescriptor(callback.definition, callback.metadata)
        val externalSchema = Json.parseToJsonElement(descriptor.withInvocationContext(AiToolContextBindings(setOf(USER))).definition.inputSchema).jsonObject
        assertEquals(setOf("conversation_id"), externalSchema.getValue("properties").jsonObject.getValue("_grz").jsonObject.getValue("properties").jsonObject.keys)
        val arguments = buildJsonObject {
            put("display_name", "New title")
            put("_grz", buildJsonObject { put("conversation_id", conversation.id.value) })
        }
        val resolver = ToolInvocationContextResolver(users, conversations, projects, agents, workspaces, workers, coordinator)
        val resolved = resolver.resolve(descriptor, arguments, user.id)
        val executor = ParallelToolExecutor(object : AiToolProvider {
            override fun getTools() = listOf(callback)
        }, AutoApproveToolApprovalService())
        val outcome = executor.executeParallel(
            listOf(Conversation.Message.ContentItem.ToolCall(Conversation.Message.ContentItem.ToolCall.Id("context-test-call"),
                Conversation.Message.ContentItem.ToolCall.Data(callback.definition.name, arguments))),
            resolved.context, null, ConversationRuntimeExecutorIdentity.Server(ConversationRuntimeServerSessionId("test")), resolved.target,
        )
        assertFalse(outcome.results.single().isError)
        val result = outcome.results.single().result.single() as Conversation.Message.ContentItem.ToolResult.Data.Text
        assertEquals("New title", Json.parseToJsonElement(result.content).jsonObject.getValue("display_name").jsonPrimitive.content)
        Mockito.verify(authorization).requireConversation(user, conversation.id, ProjectPermission.WRITE)
        Mockito.verify(service).updateDisplayName(conversation.id, "New title")
        Mockito.verifyNoInteractions(workspaces, workers, agents, coordinator)
    }

    @Test fun nativeControlFactoryDeclaresOnlyAuthenticatedIdentityAndOwnerPolicySeparately() {
        val callback = testServerToolFactory().create(CONTROL_TOOL_SOURCE, "grz_ai_catalog_get", "Catalog",
            readOnly = true, accessPolicy = AiToolAccessPolicy.SERVER_OWNER) { buildJsonObject {} }
        assertEquals(setOf(USER), callback.metadata.contextRequirements.required)
        assertEquals(AiToolAccessPolicy.SERVER_OWNER, callback.metadata.accessPolicy)
        val definition = AiToolDescriptor(callback.definition, callback.metadata)
            .withInvocationContext(AiToolContextBindings(setOf(USER))).definition
        assertFalse("_grz" in Json.parseToJsonElement(definition.inputSchema).jsonObject.getValue("properties").jsonObject)
    }
}
