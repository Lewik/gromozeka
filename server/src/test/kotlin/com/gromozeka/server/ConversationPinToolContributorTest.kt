package com.gromozeka.server

import com.gromozeka.application.service.*
import com.gromozeka.domain.model.*
import com.gromozeka.domain.service.DeclarativeStateKey
import com.gromozeka.domain.service.ProjectAccessDeniedException
import com.gromozeka.domain.tool.*
import com.gromozeka.server.ConversationPinToolSupport.Operation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.mockito.Mockito
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.core.env.MapPropertySource
import kotlin.test.*

class ConversationPinToolContributorTest {
    private fun fields(vararg values: Pair<String, JsonElement>) = JsonObject(mapOf(*values))
    private fun text(value: String) = "text" to JsonPrimitive(value)
    private fun id(value: String) = "pin_id" to JsonPrimitive(value)
    private fun revision(value: Long) = "expected_revision" to JsonPrimitive(value)
    private fun failure(code: String, action: () -> Any?): IllegalStateException {
        val error = assertFailsWith<IllegalStateException> { action() }
        assertContains(error.message.orEmpty(), "($code)")
        assertNull(error.cause); assertTrue(error.suppressed.isEmpty())
        assertFalse(error.stackTraceToString().contains("PRIVATE_"))
        return error
    }
    private suspend fun fixture() = ConversationPinToolFixture().also { it.setup() }

    @Test fun `five real callbacks are Server-only do not log text and have exactly the prepared schemas`() = runBlocking<Unit> {
        val f = fixture()
        assertEquals(Operation.entries.map { it.toolName }.toSet(), f.callbacks.keys)
        for (operation in Operation.entries) {
            val callback = f.callbacks.getValue(operation.toolName)
            assertEquals(AiToolExecutionScope.SERVER, callback.metadata.executionScope)
            assertFalse(callback.metadata.logInput); assertFalse(callback.metadata.visibleToMemoryPipeline)
            assertEquals(Json.parseToJsonElement(ConversationPinToolSupport.schema(operation)), Json.parseToJsonElement(callback.definition.inputSchema))
        }
        assertTrue(f.repository.attempts.isEmpty()); f.assertNoRuntimeEffects()
    }

    @Test fun `actual service CRUD CAS replay and no-op mutate only pins and successful invalidations`() = runBlocking<Unit> {
        val f = fixture()
        val literal = " \n literal \uD83D\uDE80\t "
        val create = f.peerArgs(text(literal))
        val created = f.call(Operation.CREATE, create, "create-once")
        val pinId = created.getValue("pin_id").jsonPrimitive.content
        assertEquals(1L, created.getValue("revision").jsonPrimitive.long)
        assertFalse("text" in created)
        assertEquals(literal, f.repository.snapshot().single().text)
        assertEquals(f.b.conversationId, f.repository.snapshot().single().conversationId)
        assertNull(f.repository.snapshot().single().source)
        assertEquals(created, f.call(Operation.CREATE, create, "create-once"))
        assertEquals(1, f.invalidations.size); assertEquals(1, f.repository.receiptCount())
        failure("PIN_CONFLICT") { f.call(Operation.CREATE, f.peerArgs(text("different payload")), "create-once") }
        val got = f.call(Operation.GET, f.peerArgs(id(pinId)))
        assertEquals(literal, got.getValue("text").jsonPrimitive.content)
        val edited = f.call(Operation.UPDATE, f.peerArgs(id(pinId), revision(1), text("edited")))
        assertEquals(2L, edited.getValue("revision").jsonPrimitive.long)
        assertEquals("edited", f.repository.snapshot().single().text)
        assertEquals(2, f.invalidations.size)
        val beforeNoOp = f.repository.snapshot()
        assertEquals(edited, f.call(Operation.UPDATE, f.peerArgs(id(pinId), revision(2), text("edited"))))
        assertEquals(beforeNoOp, f.repository.snapshot()); assertEquals(2, f.invalidations.size)
        val attempts = f.repository.attempts.size
        failure("PIN_CONFLICT") { f.call(Operation.UPDATE, f.peerArgs(id(pinId), revision(1), text("edited"))) }
        failure("PIN_CONFLICT") { f.call(Operation.DELETE, f.peerArgs(id(pinId), revision(1))) }
        assertEquals(listOf("update", "delete"), f.repository.attempts.drop(attempts))
        assertEquals(beforeNoOp, f.repository.snapshot()); assertEquals(2, f.invalidations.size)
        assertTrue(f.call(Operation.DELETE, f.peerArgs(id(pinId), revision(2))).getValue("deleted").jsonPrimitive.boolean)
        assertTrue(f.repository.snapshot().isEmpty()); assertEquals(1, f.repository.receiptCount())
        failure("PIN_NOT_FOUND") { f.call(Operation.CREATE, create, "create-once") }
        failure("PIN_NOT_FOUND") { f.call(Operation.GET, f.peerArgs(id(pinId))) }
        assertTrue(f.repository.snapshot().isEmpty())
        assertEquals(List(3) { DeclarativeStateKey.conversationPins(f.b.conversationId) }, f.invalidations)
        f.assertNoRuntimeEffects()
    }

    @Test fun `absence selects current and the same trusted create call is independently scoped by target`() = runBlocking<Unit> {
        val f = fixture()
        val current = f.call(Operation.CREATE, fields(text("current")), "same-call")
        val peer = f.call(Operation.CREATE, f.peerArgs(text("peer")), "same-call")
        assertEquals(f.a.conversationId.value, current.getValue("conversation_id").jsonPrimitive.content)
        assertEquals(f.b.conversationId.value, peer.getValue("conversation_id").jsonPrimitive.content)
        assertNotEquals(current.getValue("pin_id"), peer.getValue("pin_id"))
        assertEquals(2, f.repository.snapshot().size)
        assertEquals(1, f.call(Operation.LIST, fields()).getValue("items").jsonArray.size)
        assertEquals(1, f.call(Operation.LIST, f.peerArgs()).getValue("items").jsonArray.size)
        f.assertNoRuntimeEffects()
    }

    @Test fun `cross-conversation pin IDs never expose edit or delete the other target pin`() = runBlocking<Unit> {
        val f = fixture()
        val created = f.call(Operation.CREATE, f.peerArgs(text("peer text")))
        val pinId = created.getValue("pin_id").jsonPrimitive.content
        val before = f.repository.snapshot()
        failure("PIN_NOT_FOUND") { f.call(Operation.GET, fields(id(pinId))) }
        failure("PIN_NOT_FOUND") { f.call(Operation.UPDATE, fields(id(pinId), revision(1), text("wrong conversation"))) }
        failure("PIN_NOT_FOUND") { f.call(Operation.DELETE, fields(id(pinId), revision(1))) }
        assertEquals(before, f.repository.snapshot()); assertEquals(1, f.invalidations.size)
        assertEquals("peer text", f.call(Operation.GET, f.peerArgs(id(pinId))).getValue("text").jsonPrimitive.content)
        f.assertNoRuntimeEffects()
    }

    @Test fun `inert provenance and a changed target history branch do not become source lookups`() = runBlocking<Unit> {
        val f = fixture()
        val pin = f.core.create(f.actor.id, f.b.conversationId, "independent snapshot",
            ConversationPinSource(Conversation.Id("synthetic-deleted-source"), Conversation.Message.Id("synthetic-deleted-message")))
        f.directory[f.b.conversationId] = f.directory.getValue(f.b.conversationId).copy(currentThread = Conversation.Thread.Id("new-target-thread"))
        val result = f.call(Operation.GET, f.peerArgs(id(pin.id.value)))
        assertEquals(pin.text, result.getValue("text").jsonPrimitive.content)
        assertFalse("source" in result)
        f.assertNoRuntimeEffects()
    }

    @Test fun `every current or peer target agent is checked and unavailable targets use one category`() = runBlocking<Unit> {
        for (own in listOf(false, true)) for (reason in listOf("mixed", "empty", "missing-definition", "external", "missing", "nonparticipant", "project")) {
            val f = fixture()
            val selected = if (own) f.a else f.b
            val conversation = f.directory.getValue(selected.conversationId)
            when (reason) {
                "mixed" -> {
                    f.directory[selected.conversationId] = conversation.copy(participants = conversation.participants + Conversation.Participant.Agent(f.otherAgent))
                    f.definitions[f.otherAgent] = f.definition(f.otherAgent).copy(toolAccess = ToolAccessPolicy.AllowOnly())
                }
                "empty" -> f.directory[selected.conversationId] = conversation.copy(participants = setOf(Conversation.Participant.User(f.actor.id)))
                "missing-definition" -> f.definitions.remove(selected.agentId)
                "external" -> f.directory[selected.conversationId] = conversation.copy(externalChannel = ExternalConversationChannel("telegram", "synthetic", "chat"))
                "missing" -> f.directory.remove(selected.conversationId)
                "nonparticipant" -> f.directory[selected.conversationId] = conversation.copy(participants = setOf(Conversation.Participant.User(User.Id("other")), Conversation.Participant.Agent(selected.agentId)))
                // For self, the source project's WRITE check must deny the new project too.
                else -> {
                    val foreign = Project.Id("foreign-project")
                    f.directory[selected.conversationId] = conversation.copy(projectId = foreign)
                    Mockito.`when`(f.projectAccess.requirePermission(f.actor.id, foreign, ProjectPermission.WRITE)).thenThrow(ProjectAccessDeniedException())
                }
            }
            failure("TARGET_UNAVAILABLE") { f.call(Operation.LIST, if (own) fields() else f.peerArgs()) }
            assertTrue(f.repository.attempts.isEmpty()); assertTrue(f.invalidations.isEmpty())
            f.assertNoRuntimeEffects()
        }
    }

    @Test fun `missing inactive AI-disabled and non-writable actors cannot use even reads`() = runBlocking<Unit> {
        for (reason in listOf("missing", "inactive", "no-ai", "no-write", "source-external", "source-branch")) {
            val f = fixture()
            when (reason) {
                "missing" -> f.activeUser = null
                "inactive" -> f.activeUser = f.actor.copy(status = User.Status.DISABLED)
                "no-ai" -> f.activeUser = f.actor.copy(aiAllowed = false)
                "no-write" -> f.writeAllowed = false
                "source-external" -> f.directory[f.a.conversationId] = f.directory.getValue(f.a.conversationId).copy(externalChannel = ExternalConversationChannel("telegram", "synthetic", "chat"))
                else -> f.directory[f.a.conversationId] = f.directory.getValue(f.a.conversationId).copy(currentThread = Conversation.Thread.Id("new-source-thread"))
            }
            failure("TARGET_UNAVAILABLE") { f.call(Operation.LIST, f.peerArgs()) }
            assertTrue(f.repository.attempts.isEmpty()); f.assertNoRuntimeEffects()
        }
    }

    @Test fun `source context must be trusted complete and present for all five operations`() = runBlocking<Unit> {
        val f = fixture()
        for (operation in Operation.entries) {
            val input = validArgs(f, operation)
            failure("TARGET_UNAVAILABLE") { f.callbacks.getValue(operation.toolName).call(input.toString(), null) }
            for (missing in listOf(TOOL_CONTEXT_USER_ID, TOOL_CONTEXT_CONVERSATION_ID, TOOL_CONTEXT_AGENT_DEFINITION_ID, TOOL_CONTEXT_THREAD_ID)) {
                val context = ToolExecutionContext(f.context().asMap() - missing)
                failure("TARGET_UNAVAILABLE") { f.callbacks.getValue(operation.toolName).call(input.toString(), context) }
            }
        }
        assertTrue(f.repository.attempts.isEmpty())
        failure("PIN_OPERATION_FAILED") { f.call(Operation.CREATE, f.peerArgs(text("literal")), callId = null) }
        assertTrue(f.repository.attempts.isEmpty()); f.assertNoRuntimeEffects()
    }

    @Test fun `local invalid null cursor revision and text arguments cannot reach storage`() = runBlocking<Unit> {
        val f = fixture()
        val bad = listOf(Operation.LIST to "{\"conversation_id\":null}", Operation.LIST to "{\"cursor\":null}",
            Operation.LIST to "{\"limit\":101}", Operation.GET to "{\"pin_id\":null}",
            Operation.CREATE to "{\"text\":\" \"}", Operation.CREATE to "{\"text\":\"ok\",\"source_ref\":\"PRIVATE_INPUT\"}",
            Operation.UPDATE to "{\"pin_id\":\"synthetic-pin\",\"expected_revision\":0,\"text\":\"ok\"}",
            Operation.DELETE to "{\"pin_id\":\"synthetic-pin\",\"expected_revision\":\"1\"}")
        for ((operation, input) in bad) {
            val error = assertFailsWith<IllegalArgumentException> { f.callbacks.getValue(operation.toolName).call(input, f.context()) }
            assertNull(error.cause); assertTrue(error.suppressed.isEmpty()); assertFalse(error.toString().contains("PRIVATE_"))
        }
        assertTrue(f.repository.attempts.isEmpty()); assertTrue(f.invalidations.isEmpty())
        Mockito.verifyNoInteractions(f.conversations, f.identities, f.projectAccess, f.agents)
    }

    @Test fun `matching multiple agents with different runtime selections are allowed but prior access is not cached`() = runBlocking<Unit> {
        val f = fixture()
        f.directory[f.b.conversationId] = f.directory.getValue(f.b.conversationId).copy(participants =
            f.directory.getValue(f.b.conversationId).participants + Conversation.Participant.Agent(f.otherAgent))
        assertNotEquals(f.definitions.getValue(f.a.agentId).runtimeSelection, f.definitions.getValue(f.b.agentId).runtimeSelection)
        val created = f.call(Operation.CREATE, f.peerArgs(text("before revocation")))
        Mockito.verify(f.agents, Mockito.atLeastOnce()).findById(f.otherAgent)
        val calls = f.repository.attempts.toList()
        f.definitions[f.otherAgent] = f.definition(f.otherAgent).copy(toolAccess = ToolAccessPolicy.AllowOnly())
        failure("TARGET_UNAVAILABLE") { f.call(Operation.GET, f.peerArgs(id(created.getValue("pin_id").jsonPrimitive.content))) }
        assertEquals(calls, f.repository.attempts); assertEquals(1, f.invalidations.size)
        f.assertNoRuntimeEffects()
    }

    @Test fun `a membership change during eligibility is PIN_TARGET_CHANGED and never a partial operation`() = runBlocking<Unit> {
        val f = fixture()
        val initial = f.directory.getValue(f.b.conversationId)
        Mockito.`when`(f.conversations.findById(f.b.conversationId)).thenReturn(initial,
            initial.copy(participants = initial.participants + Conversation.Participant.Agent(f.otherAgent)))
        failure("PIN_TARGET_CHANGED") { f.call(Operation.CREATE, f.peerArgs(text("must not be created"))) }
        assertTrue(f.repository.attempts.isEmpty()); assertTrue(f.invalidations.isEmpty())
    }

    @Test fun `actual service count and aggregate quotas fail without writes invalidation or retries`() = runBlocking<Unit> {
        for (countLimit in listOf(true, false)) {
            val f = fixture()
            if (countLimit) repeat(100) { f.core.create(f.actor.id, f.b.conversationId, "seed-$it") }
            else repeat(4) { f.core.create(f.actor.id, f.b.conversationId, "x".repeat(32_000)) }
            val before = f.repository.snapshot(); val events = f.invalidations.toList(); val attempts = f.repository.attempts.size
            failure("PIN_LIMIT_EXCEEDED") { f.call(Operation.CREATE, f.peerArgs(text("one more")), "over-quota") }
            assertEquals(before, f.repository.snapshot()); assertEquals(events, f.invalidations)
            assertEquals(listOf("create"), f.repository.attempts.drop(attempts)); f.assertNoRuntimeEffects()
        }
    }

    @Test fun `an update exceeding aggregate quota preserves revision and invalidations`() = runBlocking<Unit> {
        val f = fixture()
        repeat(3) { f.core.create(f.actor.id, f.b.conversationId, "x".repeat(32_000)) }
        f.core.create(f.actor.id, f.b.conversationId, "x".repeat(31_999))
        val created = f.call(Operation.CREATE, f.peerArgs(text("y")))
        val before = f.repository.snapshot(); val events = f.invalidations.toList(); val attempts = f.repository.attempts.size
        failure("PIN_LIMIT_EXCEEDED") {
            f.call(Operation.UPDATE, f.peerArgs(id(created.getValue("pin_id").jsonPrimitive.content), revision(1), text("yz")))
        }
        assertEquals(before, f.repository.snapshot()); assertEquals(events, f.invalidations)
        assertEquals(listOf("update"), f.repository.attempts.drop(attempts)); f.assertNoRuntimeEffects()
    }

    @Test fun `unavailable attribution hides directory diagnostics and never calls core`() = runBlocking<Unit> {
        val f = fixture()
        Mockito.`when`(f.identities.findUserById(f.actor.id)).thenThrow(IllegalStateException("PRIVATE_DIRECTORY_DATA", IllegalArgumentException("PRIVATE_CAUSE")))
        failure("TARGET_UNAVAILABLE") { f.call(Operation.LIST, f.peerArgs()) }
        assertTrue(f.repository.attempts.isEmpty()); assertTrue(f.invalidations.isEmpty())
        f.assertNoRuntimeEffects()
    }

    @Test fun `actual callback list has bounded literal previews and validates cursor target before reading`() = runBlocking<Unit> {
        val f = fixture()
        repeat(3) { f.call(Operation.CREATE, f.peerArgs(text("x".repeat(255) + "\uD83D\uDE80")), "page-$it") }
        val first = f.call(Operation.LIST, f.peerArgs("limit" to JsonPrimitive(2)))
        val items = first.getValue("items").jsonArray
        assertEquals(2, items.size); assertTrue(items.none { "text" in it.jsonObject })
        assertTrue(items.all { it.jsonObject.getValue("preview").jsonPrimitive.content.length == 255 })
        val next = first.getValue("next_cursor")
        val calls = f.repository.attempts.size
        assertFailsWith<IllegalArgumentException> { f.call(Operation.LIST, fields("cursor" to next)) }
        assertEquals(calls, f.repository.attempts.size)
        assertEquals(1, f.call(Operation.LIST, f.peerArgs("cursor" to next)).getValue("items").jsonArray.size)
        f.assertNoRuntimeEffects()
    }

    @Test fun `every operation resolves its target immediately before exactly one core call`() = runBlocking<Unit> {
        val f = fixture()
        val gate = Mockito.mock(ConversationAgentAccessService::class.java)
        val core = Mockito.mock(ConversationPinApplicationService::class.java)
        val context = f.context()
        val target = f.directory.getValue(f.b.conversationId)
        val pinId = ConversationPin.Id("synthetic-pin")
        val pin = ConversationPin(pinId, target.id, "literal", 1, 1, f.now, f.now)
        val key = ConversationPinToolSupport.creationKey(f.a, f.actor.id, "synthetic-call")
        val order = mutableListOf<String>()
        Mockito.`when`(gate.source(context)).thenAnswer { order += "source"; f.a to f.actor.id }
        Mockito.`when`(gate.resolveEligibleConversation(f.a, f.actor.id, target.id.value)).thenAnswer { order += "eligible"; target }
        Mockito.`when`(core.list(f.actor.id, target.id)).thenAnswer { order += "list"; listOf(pin) }
        Mockito.`when`(core.get(f.actor.id, target.id, pinId)).thenAnswer { order += "get"; pin }
        Mockito.`when`(core.create(f.actor.id, target.id, "literal", null, key)).thenAnswer { order += "create"; pin }
        Mockito.`when`(core.update(f.actor.id, target.id, pinId, 1, "literal")).thenAnswer { order += "update"; pin }
        Mockito.`when`(core.delete(f.actor.id, target.id, pinId, 1)).thenAnswer { order += "delete"; Unit }
        val callbacks = ConversationPinToolContributor(core, gate).callbacks.associateBy { it.definition.name }
        for (operation in Operation.entries) {
            order.clear()
            callbacks.getValue(operation.toolName).call(validArgs(f, operation).toString(), context)
            assertEquals(listOf("source", "eligible", operation.suffix), order)
        }
        Mockito.verify(core).list(f.actor.id, target.id)
        Mockito.verify(core).get(f.actor.id, target.id, pinId)
        Mockito.verify(core).create(f.actor.id, target.id, "literal", null, key)
        Mockito.verify(core).update(f.actor.id, target.id, pinId, 1, "literal")
        Mockito.verify(core).delete(f.actor.id, target.id, pinId, 1)
        Mockito.verifyNoMoreInteractions(core)
        Mockito.verify(gate, Mockito.times(5)).source(context)
        Mockito.verify(gate, Mockito.times(5)).resolveEligibleConversation(f.a, f.actor.id, target.id.value)
        Mockito.verifyNoMoreInteractions(gate)
    }

    @Test fun `core reauthorization closes an actor revocation observed after eligibility`() = runBlocking<Unit> {
        val f = fixture()
        var boundaryChecks = 0
        val context = f.context().withCancellationSignal(ToolCancellationSignal {
            if (++boundaryChecks == 2) f.activeUser = null
        })
        failure("TARGET_UNAVAILABLE") {
            f.callbacks.getValue(Operation.CREATE.toolName).call(f.peerArgs(text("not committed")).toString(), context)
        }
        assertEquals(2, boundaryChecks)
        assertTrue(f.repository.attempts.isEmpty()); assertTrue(f.invalidations.isEmpty())
        f.assertNoRuntimeEffects()
    }

    @Test fun `typed backend failures are freshly sanitized and never retried`() = runBlocking<Unit> {
        val cases = listOf(
            ConversationPinNotFoundException() to "PIN_NOT_FOUND", ConversationPinConflictException() to "PIN_CONFLICT",
            ConversationPinLimitException() to "PIN_LIMIT_EXCEEDED", ConversationPinReadFailedException() to "PIN_OPERATION_FAILED",
            ConversationPinOperationFailedException() to "PIN_OPERATION_FAILED", ProjectAccessDeniedException() to "TARGET_UNAVAILABLE",
            IllegalStateException("PRIVATE_BODY") to "PIN_OPERATION_FAILED", IllegalArgumentException("PRIVATE_BODY") to "PIN_OPERATION_FAILED",
        )
        for ((original, code) in cases) {
            val f = fixture()
            original.initCause(IllegalStateException("PRIVATE_CAUSE")); original.addSuppressed(IllegalStateException("PRIVATE_SUPPRESSED"))
            val core = Mockito.mock(ConversationPinApplicationService::class.java)
            val pinId = ConversationPin.Id("synthetic-pin")
            Mockito.`when`(core.get(f.actor.id, f.b.conversationId, pinId)).thenThrow(original)
            val callback = ConversationPinToolContributor(core, f.access).callbacks.single { it.definition.name == Operation.GET.toolName }
            val error = failure(code) { callback.call(f.peerArgs(id(pinId.value)).toString(), f.context()) }
            assertNotSame(original, error)
            Mockito.verify(core).get(f.actor.id, f.b.conversationId, pinId); Mockito.verifyNoMoreInteractions(core)
            f.assertNoRuntimeEffects()
        }
    }

    @Test fun `unexpected storage failures are sanitized by actual service and callback without retries`() = runBlocking<Unit> {
        val f = fixture()
        f.repository.failure = IllegalStateException("PRIVATE_STORAGE_BODY", IllegalArgumentException("PRIVATE_CAUSE"))
        failure("PIN_OPERATION_FAILED") { f.call(Operation.CREATE, f.peerArgs(text("literal"))) }
        assertEquals(listOf("create"), f.repository.attempts); assertTrue(f.repository.snapshot().isEmpty()); assertTrue(f.invalidations.isEmpty())
        f.assertNoRuntimeEffects()
    }

    @Test fun `cancellation from context eligibility or actual repository is preserved`() = runBlocking<Unit> {
        for (phase in listOf("context", "eligibility", "repository")) {
            val f = fixture()
            val cancelled = CancellationException("Synthetic cancellation")
            var context = f.context()
            when (phase) {
                "context" -> context = context.withCancellationSignal(ToolCancellationSignal { throw cancelled })
                "eligibility" -> Mockito.`when`(f.identities.findUserById(f.actor.id)).thenThrow(cancelled)
                else -> f.repository.failure = cancelled
            }
            assertSame(cancelled, assertFailsWith<CancellationException> {
                f.callbacks.getValue(Operation.LIST.toolName).call(f.peerArgs().toString(), context)
            })
            assertEquals(if (phase == "repository") listOf("list") else emptyList(), f.repository.attempts)
            assertTrue(f.invalidations.isEmpty()); f.assertNoRuntimeEffects()
        }
    }

    @Test fun `even a faulty backend cannot leak a foreign pin's full text`() = runBlocking<Unit> {
        val f = fixture()
        val core = Mockito.mock(ConversationPinApplicationService::class.java)
        val pinId = ConversationPin.Id("synthetic-pin")
        val foreign = ConversationPin(pinId, f.a.conversationId, "PRIVATE_FOREIGN_PIN", 1, 1, f.now, f.now)
        Mockito.`when`(core.get(f.actor.id, f.b.conversationId, pinId)).thenReturn(foreign)
        val callback = ConversationPinToolContributor(core, f.access).callbacks.single { it.definition.name == Operation.GET.toolName }
        failure("PIN_NOT_FOUND") { callback.call(f.peerArgs(id(pinId.value)).toString(), f.context()) }
        Mockito.verify(core).get(f.actor.id, f.b.conversationId, pinId); Mockito.verifyNoMoreInteractions(core)
    }

    @Test fun `the five pin callbacks and real service work with collaboration disabled`() = runBlocking<Unit> {
        val f = fixture()
        AnnotationConfigApplicationContext().use { context ->
            context.environment.propertySources.addFirst(MapPropertySource("pin-test", mapOf("gromozeka.collaboration.enabled" to "false")))
            context.beanFactory.registerSingleton("pins", f.core)
            context.beanFactory.registerSingleton("pinAccess", f.access)
            context.register(ConversationPinToolContributor::class.java, AgentCollaborationToolContributor::class.java)
            context.refresh()
            assertTrue(context.getBeansOfType(AgentCollaborationToolContributor::class.java).isEmpty())
            val callback = context.getBean(ConversationPinToolContributor::class.java).callbacks.single { it.definition.name == Operation.CREATE.toolName }
            callback.call(f.peerArgs(text("not coupled to A2A")).toString(), f.context())
            assertEquals("not coupled to A2A", f.repository.snapshot().single().text)
            assertEquals(1, f.invalidations.size)
            f.assertNoRuntimeEffects()
        }
    }

    private fun validArgs(f: ConversationPinToolFixture, operation: Operation): JsonObject = when (operation) {
        Operation.LIST -> f.peerArgs()
        Operation.GET -> f.peerArgs(id("synthetic-pin"))
        Operation.CREATE -> f.peerArgs(text("literal"))
        Operation.UPDATE -> f.peerArgs(id("synthetic-pin"), revision(1), text("literal"))
        Operation.DELETE -> f.peerArgs(id("synthetic-pin"), revision(1))
    }
}
