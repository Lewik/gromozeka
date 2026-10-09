package com.gromozeka.server

import com.gromozeka.application.service.SlotApplicationService
import com.gromozeka.domain.model.*
import com.gromozeka.domain.service.UserDirectoryService
import com.gromozeka.domain.slot.*
import com.gromozeka.domain.tool.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.mockito.Mockito
import kotlin.test.*
import kotlin.time.Instant

class SlotToolContributorTest {
    @Test fun `tool schemas use numbers and never expose human approval or upfront force`() {
        val contributor = SlotToolContributor(Mockito.mock(SlotApplicationService::class.java), Mockito.mock(UserDirectoryService::class.java))
        assertEquals(7, contributor.callbacks.size)
        contributor.callbacks.forEach { callback ->
            val properties = Json.parseToJsonElement(callback.definition.inputSchema).jsonObject.getValue("properties").jsonObject
            assertFalse("force" in properties); assertFalse("user_confirmed" in properties)
            assertFalse("actor_user_id" in properties); assertFalse("conversation_id" in properties)
            properties["slot_number"]?.let { assertEquals("integer", it.jsonObject.getValue("type").jsonPrimitive.content) }
        }
        assertTrue(contributor.callbacks.none { "confirm" in it.definition.name })
        val release = contributor.callbacks.single { it.definition.name == "grz_slot_release" }
        assertFailsWith<IllegalArgumentException> { release.call("""{"slot_number":7,"lease_id":"lease","force":true}""", null) }
        val reclaim = contributor.callbacks.single { it.definition.name == "grz_slot_reclaim" }
        assertFailsWith<IllegalArgumentException> { reclaim.call("""{"slot_number":7,"lease_id":"lease","user_confirmed":true}""", null) }
    }

    @Test fun `reclaim tool can prepare but cannot consume human confirmation`() = runBlocking<Unit> {
        val now = Instant.fromEpochMilliseconds(0)
        val user = User(User.Id("user"), displayName = "User", status = User.Status.ACTIVE, createdAt = now, updatedAt = now)
        val agent = AgentDefinition.Id("agent")
        val conversation = Conversation(Conversation.Id("conversation"), Project.Id("project"),
            setOf(Conversation.Participant.User(user.id), Conversation.Participant.Agent(agent)), currentThread = Conversation.Thread.Id("thread"), createdAt = now, updatedAt = now)
        val slot = Slot("slot", 7, user.id, conversation.projectId, Workspace.Id("workspace"), WorkspaceMount.Id("mount"), createdAt = now)
        val lease = SlotLease("lease", 7, conversation.id, user.id, agent, SlotAccess.WRITE, now)
        val users = Mockito.mock(UserDirectoryService::class.java)
        val slots = Mockito.mock(SlotApplicationService::class.java)
        Mockito.`when`(users.findActiveById(user.id)).thenReturn(user)
        Mockito.`when`(slots.authorizeConversation(user, conversation.id, ProjectPermission.WRITE)).thenReturn(conversation)
        Mockito.`when`(slots.list(user)).thenReturn(listOf(SlotView(SlotSnapshot(slot, listOf(lease), emptyList()), "worker", "/clone")))
        Mockito.`when`(slots.prepareReclaim(user, lease.id)).thenReturn(lease)
        val tool = SlotToolContributor(slots, users).callbacks.single { it.definition.name == "grz_slot_reclaim" }
        val result = tool.call("""{"slot_number":7,"lease_id":"lease"}""", ToolExecutionContext(mapOf(TOOL_CONTEXT_USER_ID to user.id.value, TOOL_CONTEXT_CONVERSATION_ID to conversation.id.value)))
        assertContains(result, "awaiting_human_confirmation")
        assertContains(result, "\"released\":false")
        Mockito.verify(slots).prepareReclaim(user, lease.id)
        Mockito.verify(slots, Mockito.never()).confirmReclaim(user, lease.id, "confirmation")
    }
}
