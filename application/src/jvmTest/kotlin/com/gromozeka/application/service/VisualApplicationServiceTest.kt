package com.gromozeka.application.service

import com.gromozeka.domain.model.*
import com.gromozeka.domain.service.*
import com.gromozeka.domain.visual.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.mockito.Mockito
import kotlin.test.*
import kotlin.time.Instant

class VisualApplicationServiceTest {
    @Test fun `data replacement preserves form and failed updates are atomic`() = fixture { f ->
        val visual = f.create()
        val updated = f.service.update(f.user, f.conversation.id, visual.id, VisualUpdate(state = obj("""{"data":{"count":3}}""")))
        assertEquals(visual.state["form"], updated.state["form"])
        assertEquals(visual.formRevision, updated.formRevision)
        assertFails { f.service.update(f.user, f.conversation.id, visual.id, VisualUpdate(state = obj("""{"form":{"query":12}}"""))) }
        assertEquals(updated, f.repository.find(f.conversation.id, visual.id))
        assertFails { f.service.update(f.user, f.conversation.id, visual.id, VisualUpdate(document = "<script/>")) }
        assertEquals(updated, f.repository.find(f.conversation.id, visual.id))
    }

    @Test fun `root replacements remove omitted nested fields and required omissions fail atomically`() = fixture { f ->
        val visual = f.create()
        val populated = f.service.update(f.user, f.conversation.id, visual.id, VisualUpdate(state = obj(
            """{"form":{"query":"saved","optional":{"keep":1,"drop":2}},"data":{"count":2,"extra":{"a":1,"b":2}}}""")))
        val replaced = f.service.update(f.user, f.conversation.id, visual.id, VisualUpdate(state = obj(
            """{"form":{"query":"saved"},"data":{"count":3,"extra":{"a":4}}}""")))
        assertEquals(obj("""{"query":"saved"}"""), replaced.state.getValue("form"))
        assertEquals(obj("""{"count":3,"extra":{"a":4}}"""), replaced.state.getValue("data"))
        assertEquals(populated.formRevision + 1, replaced.formRevision)
        assertEquals(populated.documentRevision, replaced.documentRevision)
        for (invalid in listOf("{}", "{\"data\":{}}", "{\"form\":{}}", "{\"data\":null}")) {
            assertFails(invalid) { f.service.update(f.user, f.conversation.id, visual.id, VisualUpdate(state = obj(invalid))) }
            assertEquals(replaced, f.repository.find(f.conversation.id, visual.id))
        }
    }

    @Test fun `submitted form replaces all fields and programmatic form writes reset event ownership`() = fixture { f ->
        val visual = f.create()
        f.service.update(f.user, f.conversation.id, visual.id,
            VisualUpdate(state = obj("""{"form":{"query":"old","optional":1}}""")))
        f.service.act(f.user, f.conversation.id, VisualAction("event-full-form", visual.id, visual.documentRevision, "save", visual.state))
        val accepted = f.repository.find(f.conversation.id, visual.id)!!
        assertEquals(visual.state.getValue("form"), accepted.state.getValue("form"))
        assertEquals("event-full-form", accepted.formEventId)
        val dataOnly = f.service.update(f.user, f.conversation.id, visual.id,
            VisualUpdate(state = obj("""{"data":{"count":4}}""")))
        assertEquals(accepted.formRevision, dataOnly.formRevision)
        assertEquals(accepted.formEventId, dataOnly.formEventId)
        val reset = f.service.update(f.user, f.conversation.id, visual.id,
            VisualUpdate(state = buildJsonObject { put("form", accepted.state.getValue("form")) }))
        assertEquals(accepted.formRevision + 1, reset.formRevision)
        assertNull(reset.formEventId)
    }

    @Test fun `handler uses the same atomic complete section replacement contract`() = fixture { f ->
        val visual = f.create(handler = true)
        val handler = visual.handler!!
        val task = f.commands.tasks.getValue(visual.id)
        val records = listOf(
            VisualOutputRecord(100, """{"data":{"count":1,"nested":{"keep":1,"drop":2}}}"""),
            VisualOutputRecord(200, """{"data":{"count":2,"nested":{"keep":3}}}"""),
            VisualOutputRecord(300, """{"data":{"nested":{"keep":9}}}"""),
            VisualOutputRecord(400, """{"form":{"query":"reset"}}"""),
        )
        assertTrue(f.service.acceptOutput(handler.worker, visual.id, f.conversation.id, handler.generation, task, records, false))
        val saved = f.repository.find(f.conversation.id, visual.id)!!
        assertEquals(obj("""{"count":2,"nested":{"keep":3}}"""), saved.state.getValue("data"))
        assertEquals(obj("""{"query":"reset"}"""), saved.state.getValue("form"))
        assertEquals(1, saved.diagnostics.size)
        assertEquals(400L, saved.handler!!.outputCursor)
        assertNull(saved.formEventId)
        assertEquals(visual.formRevision + 1, saved.formRevision)
    }

    @Test fun `button synchronizes form but never writes back stale data and is idempotent`() = fixture { f ->
        val visual = f.create()
        f.service.update(f.user, f.conversation.id, visual.id, VisualUpdate(state = obj("""{"data":{"count":7}}""")))
        val input = obj("""{"form":{"query":"submitted"},"data":{"count":0}}""")
        val event = VisualAction("event-12345", visual.id, visual.documentRevision, "save", input)
        assertTrue(f.service.act(f.user, f.conversation.id, event).accepted)
        val saved = f.repository.find(f.conversation.id, visual.id)!!
        assertEquals("submitted", saved.state["form"]!!.jsonObject["query"]!!.jsonPrimitive.content)
        assertEquals(7, saved.state["data"]!!.jsonObject["count"]!!.jsonPrimitive.int)
        assertEquals(event.eventId, saved.formEventId)
        assertEquals(input, f.events.single().snapshot)
        assertTrue(f.service.act(f.user, f.conversation.id, event).accepted)
        assertEquals(1, f.events.size)
        assertFails { f.service.act(f.user, f.conversation.id, event.copy(state = visual.state)) }
    }

    @Test fun `button action stores submitted labels and snapshot independently of later visual edits`() = fixture { f ->
        val original = f.create()
        val visual = f.service.update(f.user, f.conversation.id, original.id,
            VisualUpdate(document = markup("<button id='save'>Save <span>{data.count}</span></button>")))
        f.service.update(f.user, f.conversation.id, visual.id, VisualUpdate(state = obj("""{"data":{"count":9}}""")))
        val action = VisualAction("event-capture-1", visual.id, visual.documentRevision, "save", visual.state)
        assertTrue(f.service.act(f.user, f.conversation.id, action).accepted)
        val captured = f.events.single()
        assertEquals("Test", captured.visualTitle)
        assertEquals("Save 0", captured.buttonLabel)
        assertEquals(visual.state, captured.snapshot)
        assertEquals(9, f.repository.find(f.conversation.id, visual.id)!!.state["data"]!!.jsonObject["count"]!!.jsonPrimitive.int)
        f.service.update(f.user, f.conversation.id, visual.id,
            VisualUpdate(document = markup("<button id='save'>Changed</button>").replace("<title>Test</title>", "<title>Changed</title>")))
        assertEquals(captured, f.events.single())
        assertEquals("Save 0", f.events.single().buttonLabel)
    }

    @Test fun `hidden disabled and stale buttons cannot invoke handlers`() = fixture { f ->
        val visual = f.create()
        val event = VisualAction("event-23456", visual.id, visual.documentRevision, "save", visual.state)
        assertFails { f.service.act(f.user, f.conversation.id, event.copy(buttonId = "missing")) }
        assertFails { f.service.act(f.user, f.conversation.id, event.copy(documentRevision = 0)) }
        f.service.update(f.user, f.conversation.id, visual.id, VisualUpdate(document = markup("<button id='save' disabled='true'>Save</button>")))
        val current = f.repository.find(f.conversation.id, visual.id)!!
        assertFails { f.service.act(f.user, f.conversation.id, event.copy(documentRevision = current.documentRevision)) }
        assertTrue(f.events.isEmpty())
    }

    @Test fun `foreign conversation and nonparticipant access is denied`() = fixture { f ->
        val visual = f.create()
        val stranger = f.user.copy(id = User.Id("stranger"))
        assertFailsWith<ProjectAccessDeniedException> { f.service.list(stranger, f.conversation.id) }
        assertFailsWith<ProjectAccessDeniedException> { f.service.close(f.user, Conversation.Id("other"), visual.id) }
        assertNotNull(f.repository.find(f.conversation.id, visual.id))
    }

    @Test fun `worker output is owner checked bounded validated and replay safe`() = fixture { f ->
        val visual = f.create(handler = true)
        val handler = visual.handler!!
        val task = f.commands.tasks.getValue(visual.id)
        val record = VisualOutputRecord(40, """{"form":{"query":"from-worker"},"data":{"count":4}}""")
        assertTrue(f.service.acceptOutput(handler.worker, visual.id, f.conversation.id, handler.generation, task, listOf(record), false))
        val state = f.repository.find(f.conversation.id, visual.id)!!.state
        f.service.act(f.user, f.conversation.id, VisualAction("event-34567", visual.id, visual.documentRevision, "save",
            replaceVisualStateSections(state, obj("""{"form":{"query":"from-user"}}"""))))
        f.service.acceptOutput(handler.worker, visual.id, f.conversation.id, handler.generation, task, listOf(record), false)
        assertEquals("from-user", f.repository.find(f.conversation.id, visual.id)!!.state["form"]!!.jsonObject["query"]!!.jsonPrimitive.content)
        assertFails { f.service.acceptOutput(handler.worker.copy(workerId = ConversationRuntimeWorkerId("other")), visual.id, f.conversation.id, handler.generation, task, listOf(record.copy(endByte = 50)), false) }
        f.service.acceptOutput(handler.worker, visual.id, f.conversation.id, handler.generation, task,
            listOf(VisualOutputRecord(60, "not json"), VisualOutputRecord(80, """{"data":{"count":5}}""")), false)
        val updated = f.repository.find(f.conversation.id, visual.id)!!
        assertEquals(5, updated.state["data"]!!.jsonObject["count"]!!.jsonPrimitive.int)
        assertEquals(1, updated.diagnostics.size)
        assertEquals(80L, updated.handler!!.outputCursor)
    }

    @Test fun `replaced handler cannot overwrite the same visual and close cancels ownership`() = fixture { f ->
        val visual = f.create(handler = true)
        val old = visual.handler!!
        val task = f.commands.tasks.getValue(visual.id)
        val manual = f.service.update(f.user, f.conversation.id, visual.id, VisualUpdate(updateHandler = true))
        assertEquals(visual.id, manual.id)
        assertNull(manual.handler)
        assertFalse(f.service.acceptOutput(old.worker, visual.id, f.conversation.id, old.generation, task, listOf(VisualOutputRecord(40, "{}")), false))
        assertTrue(f.commands.cancelled.any { it.id == visual.id })
        val script = f.service.update(f.user, f.conversation.id, visual.id, VisualUpdate(updateHandler = true, handler = f.spec))
        assertNotEquals(old.generation, script.handler!!.generation)
        f.service.close(f.user, f.conversation.id, visual.id)
        assertTrue(f.service.list(f.user, f.conversation.id).isEmpty())
        assertEquals(2, f.commands.cancelled.size)
    }

    @Test fun `blocked stdin does not prevent closing the visual`() = fixture { f ->
        val visual = f.create(handler = true)
        f.commands.inputGate = CompletableDeferred()
        val sent = async { f.service.act(f.user, f.conversation.id, VisualAction("event-45678", visual.id, visual.documentRevision, "save", visual.state)) }
        f.commands.inputStarted.await()
        withTimeout(1_000) { f.service.close(f.user, f.conversation.id, visual.id) }
        assertTrue(f.commands.cancelled.isNotEmpty())
        f.commands.inputGate!!.complete(Unit)
        sent.await()
    }

    @Test fun `delivery failure keeps accepted form and is never automatically resent`() = fixture { f ->
        val visual = f.create(handler = true)
        f.commands.failInput = true
        val event = VisualAction("event-56789", visual.id, visual.documentRevision, "save", replaceVisualStateSections(visual.state,obj("""{"form":{"query":"retained"}}""")))
        assertFalse(f.service.act(f.user,f.conversation.id,event).accepted)
        assertFalse(f.service.act(f.user,f.conversation.id,event).accepted)
        assertEquals(1,f.commands.inputs.size)
        val saved=f.repository.find(f.conversation.id,visual.id)!!
        assertEquals("retained",saved.state["form"]!!.jsonObject["query"]!!.jsonPrimitive.content)
        assertEquals("action-delivery",saved.diagnostics.single().code)
    }

    @Test fun `terminal output stops rather than restarts handler`() = fixture { f ->
        val visual=f.create(handler=true);val handler=visual.handler!!
        val task=f.commands.tasks.getValue(visual.id).copy(status=CommandTask.Status.COMPLETED,exitCode=0)
        assertFalse(f.service.acceptOutput(handler.worker,visual.id,f.conversation.id,handler.generation,task,listOf(VisualOutputRecord(20,"""{"data":{"count":9}}""")),true))
        val stopped=f.repository.find(f.conversation.id,visual.id)!!
        assertEquals(VisualStatus.STOPPED,stopped.status)
        assertEquals(9,stopped.state["data"]!!.jsonObject["count"]!!.jsonPrimitive.int)
        assertFails { f.service.act(f.user,f.conversation.id,VisualAction("event-67890",visual.id,visual.documentRevision,"save",stopped.state)) }
        assertEquals(1,f.commands.starts)
    }

    @Test fun `highlight validates IDs and sends no form changes or handler input`() = fixture { f ->
        val visual = f.create()
        assertEquals(1, f.service.highlight(f.user, f.conversation.id, visual.id, listOf("save")))
        assertEquals(listOf("save"), f.highlights.single().elementIds)
        assertEquals(visual.documentRevision, f.highlights.single().documentRevision)
        assertEquals(visual, f.repository.find(f.conversation.id, visual.id))
        assertTrue(f.events.isEmpty())
        assertTrue(f.commands.inputs.isEmpty())
        assertFails { f.service.highlight(f.user, f.conversation.id, visual.id, listOf("missing")) }
        assertFails { f.service.highlight(f.user, f.conversation.id, visual.id, listOf("save", "save")) }
        assertFailsWith<ProjectAccessDeniedException> { f.service.highlight(f.user.copy(id = User.Id("stranger")), f.conversation.id, visual.id, emptyList()) }
        assertEquals(1, f.highlights.size)
        f.service.highlight(f.user, f.conversation.id, visual.id, emptyList())
        assertTrue(f.highlights.last().elementIds.isEmpty())
        assertEquals(visual, f.repository.find(f.conversation.id, visual.id))
    }

    private fun fixture(block: suspend CoroutineScope.(Fixture) -> Unit) = runBlocking {
        val f=Fixture()
        try { block(f) } finally { f.scope.cancel() }
    }

    private class Fixture {
        val now=Instant.parse("2026-10-04T00:00:00Z")
        val user=User(User.Id("user-1"),displayName="Test user",status=User.Status.ACTIVE,createdAt=now,updatedAt=now)
        val conversation=Conversation(Conversation.Id("conversation-1"),Project.Id("project-1"),setOf(Conversation.Participant.User(user.id)),currentThread=Conversation.Thread.Id("thread-1"),createdAt=now,updatedAt=now)
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val repository=MemoryVisuals()
        val commands=FakeCommands()
        val events=mutableListOf<Conversation.Message.ContentItem.VisualInteraction>()
        val highlights = mutableListOf<VisualHighlightCommand>()
        val spec=VisualHandlerSpec(WorkspaceMount.Id("mount-1"),"handler")
        val service: VisualApplicationService
        init {
            val conversations=Mockito.mock(ConversationDomainService::class.java)
            runBlocking { Mockito.`when`(conversations.findById(conversation.id)).thenReturn(conversation) }
            service=VisualApplicationService(repository,conversations,Mockito.mock(ProjectAccessService::class.java),commands,
                VisualInteractionDelivery { _,_,input -> events.add(input);true },scope,
                VisualHighlightDelivery { actor, command -> assertEquals(user.id, actor); highlights.add(command); 1 })
        }
        suspend fun create(handler: Boolean=false)=service.create(user,conversation.id,VisualCreate(markup(),obj("""{"form":{"query":""},"data":{"count":0}}"""),if(handler)spec else null))
    }

    private class FakeCommands : VisualCommandRuntime {
        val tasks=mutableMapOf<String,CommandTask>()
        val cancelled=mutableListOf<Visual>()
        val inputs=mutableListOf<String>()
        var starts=0
        var failInput=false
        var inputGate:CompletableDeferred<Unit>?=null
        val inputStarted=CompletableDeferred<Unit>()
        private val owner=ConversationRuntimeWorkerIdentity(ConversationRuntimeWorkerId("worker-1"),ConversationRuntimeWorkerSessionId("session-1"))
        override suspend fun prepare(actor:User,conversation:Conversation,spec:VisualHandlerSpec)=VisualHandler(spec,owner,"generation-${starts+1}")
        override suspend fun start(actor:User,visual:Visual):CommandTask {
            starts++
            return CommandTask(CommandTask.Id("task-$starts"),visual.conversationId,owner.workerId,visual.handler!!.spec.workspaceMountId,
                visualId=visual.id,command="handler",workingDirectory="/tmp",status=CommandTask.Status.WORKING,processId=1,
                processStartedAt=visual.createdAt,outputFile="/tmp/visual-output",outputBytes=100_000,createdAt=visual.createdAt,updatedAt=visual.createdAt)
                .also { tasks[visual.id]=it }
        }
        override suspend fun sendInput(actor:User,visual:Visual,input:String) {
            inputs.add(input);inputStarted.complete(Unit);inputGate?.await();if(failInput)error("Pipe write failed")
        }
        override suspend fun cancel(visual:Visual) { cancelled.add(visual) }
        override suspend fun failure(visual:Visual):String?=null
    }

    private class MemoryVisuals : VisualRepository {
        private val values=mutableMapOf<String,Visual>()
        private val actions=mutableMapOf<String,VisualActionReceipt>()
        override suspend fun list(conversationId:Conversation.Id)=values.values.filter { it.conversationId==conversationId }
        override suspend fun find(conversationId:Conversation.Id,visualId:String)=values[visualId]?.takeIf { it.conversationId==conversationId }
        override suspend fun save(visual:Visual) {
            check(visual.revision==(values[visual.id]?.revision?:0)+1);values[visual.id]=visual
        }
        override suspend fun delete(conversationId:Conversation.Id,visualId:String):Boolean {
            if(find(conversationId,visualId)==null)return false
            values.remove(visualId);actions.entries.removeIf { it.value.visualId==visualId };return true
        }
        override suspend fun withHandlers()=values.values.filter { it.handler!=null && it.status!=VisualStatus.STOPPED }
        override suspend fun findAction(eventId:String)=actions[eventId]
        override suspend fun acceptAction(visual:Visual,receipt:VisualActionReceipt):Boolean {
            if(receipt.eventId in actions)return false
            save(visual);actions[receipt.eventId]=receipt;return true
        }
        override suspend fun finishAction(eventId:String,result:VisualActionResult) { actions[eventId]?.let { actions[eventId]=it.copy(result=result) } }
    }

    companion object {
        private fun obj(text:String)=Json.parseToJsonElement(text).jsonObject
        private fun markup(body:String="<input name='form.query'/><button id='save'>Save</button>")="""<html><head><title>Test</title><state-schema><![CDATA[{"type":"object","properties":{"form":{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]},"data":{"type":"object","properties":{"count":{"type":"integer"}},"required":["count"]}},"required":["form","data"]}]]></state-schema></head><body>$body</body></html>"""
    }
}
