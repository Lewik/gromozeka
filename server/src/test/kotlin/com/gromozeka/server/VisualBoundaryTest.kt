package com.gromozeka.server

import com.gromozeka.application.service.VisualApplicationService
import com.gromozeka.domain.model.*
import com.gromozeka.domain.service.*
import com.gromozeka.domain.tool.*
import com.gromozeka.domain.visual.*
import com.gromozeka.remote.protocol.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.mockito.Mockito
import kotlin.test.*
import kotlin.time.Instant
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

class VisualBoundaryTest {
    private val now = Instant.fromEpochMilliseconds(0)
    private val user = User(User.Id("user"), displayName = "User", status = User.Status.ACTIVE, createdAt = now, updatedAt = now)
    private val conversation = Conversation(Conversation.Id("conversation"), Project.Id("project"),
        setOf(Conversation.Participant.User(user.id)), currentThread = Conversation.Thread.Id("thread"), createdAt = now, updatedAt = now)
    private val document = """<html><head><title>Test</title><state-schema><![CDATA[{"type":"object","properties":{"form":{"type":"object"},"data":{"type":"object"}},"required":["form","data"]}]]></state-schema></head><body><button id="submit">Submit</button></body></html>"""
    private val state = Json.parseToJsonElement("""{"form":{"query":"hello"},"data":{"count":1}}""").jsonObject
    private val visual = Visual("visual", conversation.id, user.id, AgentDefinition.Id("agent"), document, "Test", state,
        createdAt = now, updatedAt = now)
    private val service = Mockito.mock(VisualApplicationService::class.java)
    private val tool = VisualToolContributor(service, object : UserDirectoryService {
        override suspend fun findActiveById(id: User.Id) = user.takeIf { it.id == id }
        override suspend fun listActive() = listOf(user)
    }).callbacks.single()
    private val context = ToolExecutionContext(mapOf(TOOL_CONTEXT_USER_ID to user.id.value,
        TOOL_CONTEXT_CONVERSATION_ID to conversation.id.value, TOOL_CONTEXT_AGENT_DEFINITION_ID to "agent"))

    @Test fun `reference is authenticated and needs no conversation`() {
        val reference = tool.call("""{"action":"reference"}""", ToolExecutionContext(mapOf(TOOL_CONTEXT_USER_ID to user.id.value)))
        assertContains(reference, "8 KiB")
        assertContains(reference, "form")
        assertEquals(AiToolExecutionScope.SERVER, tool.metadata.executionScope)
        assertFalse(tool.metadata.logInput)
        assertFalse(tool.metadata.visibleToMemoryPipeline)
        assertFalse(result(tool.call("""{"action":"reference"}""", null)).getValue("success").jsonPrimitive.boolean)
        Mockito.verifyNoInteractions(service)
    }

    @Test fun `create acknowledges ownership without echoing document or state`() = runBlocking<Unit> {
        Mockito.`when`(service.create(user, conversation.id, VisualCreate(document, state), AgentDefinition.Id("agent"))).thenReturn(visual)
        val response = result(tool.call(buildJsonObject { put("action", "create"); put("document", document); put("state", state) }.toString(), context))
        assertTrue(response.getValue("success").jsonPrimitive.boolean)
        assertEquals(visual.id, response.getValue("visual_id").jsonPrimitive.content)
        assertEquals(visual.revision, response.getValue("revision").jsonPrimitive.long)
        assertFalse("visual" in response)
        assertFalse("document" in response)
        assertFalse("state" in response)
        assertFalse(response.toString().contains("<html>"))
        Mockito.verify(service).create(user, conversation.id, VisualCreate(document, state), AgentDefinition.Id("agent"))
    }

    @Test fun `tool distinguishes absent handler from explicit detach`() = runBlocking<Unit> {
        val sections = buildJsonObject { put("data", buildJsonObject { put("count", 2) }) }
        Mockito.`when`(service.update(user, conversation.id, visual.id, VisualUpdate(state = sections))).thenReturn(visual)
        Mockito.`when`(service.update(user, conversation.id, visual.id, VisualUpdate(updateHandler = true))).thenReturn(visual.copy(handler = null))
        assertTrue(result(tool.call("""{"action":"update","visual_id":"visual","state":{"data":{"count":2}}}""", context)).getValue("success").jsonPrimitive.boolean)
        assertTrue(result(tool.call("""{"action":"update","visual_id":"visual","handler":null}""", context)).getValue("success").jsonPrimitive.boolean)
        Mockito.verify(service).update(user, conversation.id, visual.id, VisualUpdate(state = sections))
        Mockito.verify(service).update(user, conversation.id, visual.id, VisualUpdate(updateHandler = true))
    }

    @Test fun `update receipts stay compact even for a large document and expose stopped diagnostics`() = runBlocking<Unit> {
        val sections = buildJsonObject { put("data", buildJsonObject { put("count", 2) }) }
        val saved = visual.copy(document = "x".repeat(60_000), state = state,
            revision = 7, status = VisualStatus.STOPPED,
            diagnostics = listOf(VisualDiagnostic("handler-stopped", "Handler stopped", now)))
        Mockito.`when`(service.update(user, conversation.id, visual.id, VisualUpdate(state = sections))).thenReturn(saved)
        val text = tool.call("""{"action":"update","visual_id":"visual","state":{"data":{"count":2}}}""", context)
        val response = result(text)
        assertTrue(response.getValue("success").jsonPrimitive.boolean)
        assertEquals("STOPPED", response.getValue("status").jsonPrimitive.content)
        assertEquals(7, response.getValue("revision").jsonPrimitive.int)
        assertEquals(1, response.getValue("diagnostics").jsonArray.size)
        assertTrue(text.length < 1_000)
        assertFalse("document" in response)
        assertFalse("state" in response)
    }

    @Test fun `only get returns the exact full document state and effective palette`() = runBlocking<Unit> {
        Mockito.`when`(service.list(user, conversation.id)).thenReturn(listOf(visual))
        val response = result(tool.call("""{"action":"get","visual_id":"visual"}""", context))
        assertEquals(document, response.getValue("visual").jsonObject.getValue("document").jsonPrimitive.content)
        assertEquals(state, response.getValue("visual").jsonObject.getValue("state"))
        assertFalse("formEventId" in response.getValue("visual").jsonObject)
        assertEquals("#DC2626", response.getValue("palette").jsonObject.getValue("red").jsonPrimitive.content)
        val listing = result(tool.call("""{"action":"list"}""", context)).getValue("visuals").jsonArray.single().jsonObject
        assertEquals(visual.id, listing.getValue("visual_id").jsonPrimitive.content)
        assertFalse("document" in listing)
        assertFalse("state" in listing)
    }

    @Test fun `removed and irrelevant arguments are rejected instead of silently ignored`() {
        for (input in listOf(
            """{"action":"update","visual_id":"visual","patch":{"data":{}}}""",
            """{"action":"get","visual_id":"visual","state":{"data":{}}}""",
            """{"action":"update","visual_id":"visual","state":null}""",
            """{"action":"update","visual_id":"visual","document":123}""",
        )) assertFalse(result(tool.call(input, context)).getValue("success").jsonPrimitive.boolean, input)
        Mockito.verifyNoInteractions(service)
        val properties = result(VisualToolContributor.SCHEMA).getValue("properties").jsonObject
        assertFalse("patch" in properties)
        val stateSchema = properties.getValue("state").jsonObject
        assertEquals(setOf("form", "data"), stateSchema.getValue("properties").jsonObject.keys)
        assertFalse("required" in stateSchema) // update may supply either complete section.
        assertEquals(1, stateSchema.getValue("minProperties").jsonPrimitive.int)
        assertFalse(VisualToolContributor.DESCRIPTION.contains("patch", ignoreCase = true))
        assertFalse(VisualToolContributor.REFERENCE.contains("patch", ignoreCase = true))
    }

    @Test fun `tool refuses caller selected context and missing context`() {
        assertFalse(result(tool.call("""{"action":"list","conversation_id":"foreign"}""", context)).getValue("success").jsonPrimitive.boolean)
        assertFalse(result(tool.call("""{"action":"list"}""", ToolExecutionContext(mapOf(TOOL_CONTEXT_USER_ID to user.id.value)))).getValue("success").jsonPrimitive.boolean)
        Mockito.verifyNoInteractions(service)
    }

    @Test fun `LLM receives same JSON snapshot as handler with explicit actor agent and safe placement`() = runBlocking<Unit> {
        val input = Conversation.Message.ContentItem.VisualInteraction(visual.id, visual.title, visual.documentRevision, "event-123456", "submit", "Submit", state)
        var received: Conversation.Message? = null
        val ingress = object : ConversationRuntimeIngressService {
            override suspend fun postMessage(actorUser: User, conversationId: Conversation.Id, userMessage: Conversation.Message): Boolean = error("Must route to owning agent")
            override suspend fun invokeAgent(actorUser: User, conversationId: Conversation.Id, userMessage: Conversation.Message, agentDefinitionId: AgentDefinition.Id): Boolean = error("Must queue safely")
            override suspend fun enqueueAgentInvocation(actorUser: User, conversationId: Conversation.Id, userMessage: Conversation.Message, agentDefinitionId: AgentDefinition.Id, placement: QueuedMessagePlacement): Boolean {
                assertEquals(user, actorUser)
                assertEquals(conversation.id, conversationId)
                assertEquals(visual.agentDefinitionId, agentDefinitionId)
                assertEquals(QueuedMessagePlacement.AFTER_TOOL_RESULT, placement)
                received = userMessage
                return true
            }
        }
        val delivery = ConversationVisualInteractionDelivery(ingress)
        assertTrue(delivery.send(user, visual, input))
        val message = assertNotNull(received)
        assertEquals(Conversation.Message.Role.USER, message.role)
        assertEquals(Conversation.Message.Author.User(user.id, user.displayName), message.author)
        assertEquals(listOf(input), message.content)
        assertEquals("visual_action", message.providerMetadata?.get("inputSource")?.jsonPrimitive?.content)
        received = null
        assertFails { delivery.send(user.copy(aiAllowed = false), visual, input) }
        assertFails { delivery.send(user, visual.copy(agentDefinitionId = null), input) }
        assertNull(received)
    }

    @Test fun `visual routes enforce read write and participant boundaries`() = runBlocking<Unit> {
        val access = Mockito.mock(ProjectAccessService::class.java)
        val conversations = Mockito.mock(ConversationDomainService::class.java)
        Mockito.`when`(conversations.findById(conversation.id)).thenReturn(conversation)
        val authorization = GromozekaRemoteAuthorization(access, conversations, Mockito.mock(AgentDomainService::class.java),
            Mockito.mock(PromptDomainService::class.java), Mockito.mock(AgentSkillDomainService::class.java), Mockito.mock(WorkspaceDomainService::class.java))
        authorization.authorize(user, ListVisualsRequest(conversation.id))
        Mockito.verify(access).requirePermission(user.id, conversation.projectId, ProjectPermission.READ)
        authorization.authorize(user, CreateVisualRequest(conversation.id, VisualCreate(document, state)))
        Mockito.verify(access).requirePermission(user.id, conversation.projectId, ProjectPermission.WRITE)
        assertFailsWith<ProjectAccessDeniedException> { authorization.authorize(user.copy(id = User.Id("stranger")), CloseVisualRequest(conversation.id, visual.id)) }
    }

    @Test fun `temporary Worker disconnects do not stop handlers but new sessions and terminal tasks do`() = runBlocking<Unit> {
        val workers = Mockito.mock(ConversationRuntimeWorkerRegistry::class.java)
        val commands = Mockito.mock(CommandRuntimeStateService::class.java)
        val gateway = GatewayVisualCommandRuntime(
            Mockito.mock(WorkerRequestService::class.java), Mockito.mock(WorkspaceDomainService::class.java), workers,
            Mockito.mock(WorkerAccessService::class.java), commands, Mockito.mock(ConversationRuntimeCoordinator::class.java),
            Mockito.mock(ConversationDomainService::class.java), Mockito.mock(com.gromozeka.application.service.NamedSecretApplicationService::class.java),
            Mockito.mock(com.gromozeka.application.service.SlotApplicationService::class.java),
        )
        val identity = ConversationRuntimeWorkerIdentity(ConversationRuntimeWorkerId("worker"), ConversationRuntimeWorkerSessionId("session"))
        val taskId = CommandTask.Id("handler-task")
        val owned = visual.copy(handler = VisualHandler(VisualHandlerSpec(WorkspaceMount.Id("mount"), "handler"), identity, "generation", taskId))
        assertNull(gateway.failure(owned)) // Registry has not been restored yet.
        val registration = ConversationRuntimeWorkerRegistration(identity,
            setOf(ConversationRuntimeCapability.TOOL_EXECUTION), emptyList(), Mockito.mock(WorkerEnvironmentProfile::class.java),
            "test", now, now, stoppedAt = now)
        Mockito.`when`(workers.find(identity.workerId)).thenReturn(registration)
        assertNull(gateway.failure(owned)) // Same Worker process, stale/offline gateway connection.
        Mockito.`when`(workers.find(identity.workerId)).thenReturn(registration.copy(identity = identity.copy(sessionId = ConversationRuntimeWorkerSessionId("new-session"))))
        assertContains(assertNotNull(gateway.failure(owned)), "restarted")
        Mockito.`when`(workers.find(identity.workerId)).thenReturn(registration)
        val task = Mockito.mock(CommandTask::class.java)
        Mockito.`when`(commands.findCommandTask(conversation.id, taskId)).thenReturn(task)
        Mockito.`when`(task.isTerminal).thenReturn(true)
        Mockito.`when`(task.completedAt).thenReturn(Clock.System.now())
        assertNull(gateway.failure(owned)) // Let the final output reach the Server first.
        Mockito.`when`(task.completedAt).thenReturn(Clock.System.now() - 20.seconds)
        assertContains(assertNotNull(gateway.failure(owned)), "Handler stopped")
    }

    private fun result(text: String) = Json.parseToJsonElement(text).jsonObject
}
