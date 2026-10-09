package com.gromozeka.presentation.ui.session

import androidx.compose.ui.test.*
import com.gromozeka.domain.model.*
import com.gromozeka.domain.slot.*
import com.gromozeka.presentation.ui.GromozekaTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*
import kotlin.time.Instant
import kotlin.time.Duration.Companion.minutes

class SlotInventoryTest {
    private val now = Instant.parse("2026-10-09T10:00:00Z")
    private val conversation = Conversation.Id("conversation")
    private fun view(number: Long, pending: Boolean = false): SlotView {
        val slot = Slot("slot-$number", number, User.Id("user"), Project.Id("project"), Workspace.Id("ws-$number"), WorkspaceMount.Id("mount-$number"), createdAt = now)
        val lease = SlotLease("lease-$number", number, conversation, slot.userId, AgentDefinition.Id("agent"), SlotAccess.WRITE, now)
        val request = SlotRequest("request-$number", "key-$number", number, conversation, slot.userId, lease.agentId, SlotAccess.WRITE, now)
        return SlotView(SlotSnapshot(slot, if (pending) emptyList() else listOf(lease), if (pending) listOf(request) else emptyList()),
            "worker", "/checkout-$number", mapOf(conversation.value to "Example conversation"))
    }

    @Test fun `header distinguishes held slots from waiting and does not copy them to a fork`() {
        val views = listOf(view(12), view(7), view(15, true))
        assertEquals(SlotHeaderOccupancy(listOf(7, 12), listOf(15)), slotHeaderOccupancy(views, conversation))
        assertEquals(SlotHeaderOccupancy(emptyList(), emptyList()), slotHeaderOccupancy(views, Conversation.Id("fork")))
    }

    @Test fun `opening inventory or preparing reclaim does not approve it`() = runComposeUiTest {
        val service = FakeService(listOf(view(7)))
        setContent { GromozekaTheme { SlotHeaderButton(service, conversation) {} } }
        onNodeWithTag("conversation-slots").assertTextContains("7", substring = true).performClick()
        onNodeWithTag("slot-inventory").assertExists()
        runOnIdle { assertEquals(0, service.confirmations) }
        onNodeWithTag("reclaim-lease-7").performClick()
        onNodeWithTag("confirm-slot-reclaim").assertExists()
        runOnIdle { assertEquals(1, service.preparations); assertEquals(0, service.confirmations) }
        onNodeWithTag("confirm-slot-reclaim").performClick()
        runOnIdle { assertEquals(1, service.confirmations) }
    }

    private inner class FakeService(views: List<SlotView>) : SlotService {
        val state = MutableStateFlow(views)
        var preparations = 0
        var confirmations = 0
        override fun observe() = state
        override suspend fun list() = state.value
        override suspend fun prepareReclaim(leaseId: String): SlotLease {
            preparations++
            return state.value.flatMap { it.snapshot.leases }.single { it.id == leaseId }
                .copy(reclaimConfirmation = SlotReclaimConfirmation("confirmation", now, now + 15.minutes))
        }
        override suspend fun confirmReclaim(leaseId: String, confirmationId: String): SlotLease {
            assertEquals("confirmation", confirmationId); confirmations++
            return state.value.flatMap { it.snapshot.leases }.single { it.id == leaseId }.copy(releasedAt = now)
        }
        override suspend fun cancelRequest(requestId: String): SlotRequest = error("Not used")
    }
}
