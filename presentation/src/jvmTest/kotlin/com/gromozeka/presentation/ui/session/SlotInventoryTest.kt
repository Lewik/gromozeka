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

    @Test fun `badge distinguishes held slots from waiting and does not copy them to a fork`() {
        val views = listOf(view(12), view(7), view(15, true))
        assertEquals(ConversationSlotOccupancy(listOf(7, 12), listOf(15)), conversationSlotOccupancy(views, conversation))
        assertEquals(ConversationSlotOccupancy(emptyList(), emptyList()), conversationSlotOccupancy(views, Conversation.Id("fork")))
    }

    @Test fun `opening inventory or preparing reclaim does not approve it`() = runComposeUiTest {
        val service = FakeService(listOf(view(7)))
        setContent { GromozekaTheme { InventoryFixture(service) } }
        onNodeWithTag("conversation-slots").assertTextContains("1", substring = true).performClick()
        onNodeWithTag("slot-inventory").assertExists()
        runOnIdle { assertEquals(0, service.confirmations) }
        onNodeWithTag("reclaim-lease-7").performClick()
        onNodeWithTag("confirm-slot-reclaim").assertExists()
        runOnIdle { assertEquals(1, service.preparations); assertEquals(0, service.confirmations) }
        onNodeWithTag("confirm-slot-reclaim").performClick()
        runOnIdle { assertEquals(1, service.confirmations) }
    }

    @Test fun `unbound automatic waits appear in the badge and empty catalog and can be cancelled`() = runComposeUiTest {
        val request = SlotRequest("automatic", "key", null, conversation, User.Id("user"), AgentDefinition.Id("agent"), SlotAccess.READ, now, projectId = Project.Id("project"))
        val service = FakeService(emptyList(), listOf(request))
        assertEquals(ConversationSlotOccupancy(emptyList(), emptyList(), 1), conversationSlotOccupancy(emptyList(), conversation, listOf(request)))
        assertEquals(0, conversationSlotOccupancy(emptyList(), Conversation.Id("fork"), listOf(request)).automaticPending)
        setContent { GromozekaTheme { InventoryFixture(service) } }
        onNode(hasContentDescription("Automatic selection (1)", substring = true) and hasAnyAncestor(hasTestTag("conversation-slots")), useUnmergedTree = true).assertExists()
        onNodeWithTag("conversation-slots").performClick()
        onNodeWithTag("automatic-slot-request-automatic").assertExists()
        onNodeWithTag("cancel-slot-request-automatic").performClick()
        runOnIdle { assertEquals(listOf("automatic"), service.cancelled) }
        onNodeWithTag("automatic-slot-request-automatic").assertDoesNotExist()
    }

    @androidx.compose.runtime.Composable
    private fun InventoryFixture(service: SlotService) {
        val state = androidx.compose.runtime.remember(service) { SlotInventoryState() }
        val open = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
        androidx.compose.runtime.LaunchedEffect(service, state.observationAttempt) { state.observe(service) }
        if (open.value) SlotInventoryPanel(state, service, {})
        else TestFooterSummary(null, emptyList(), null, null, {}, conversationId = conversation,
            slotLoad = state.load, onOpenSlots = { open.value = true })
    }

    private inner class FakeService(views: List<SlotView>, automatic: List<SlotRequest> = emptyList()) : SlotService {
        val state = MutableStateFlow(SlotCatalogSnapshot(views, automatic))
        val cancelled = mutableListOf<String>()
        var preparations = 0
        var confirmations = 0
        override fun observe() = state
        override suspend fun list() = state.value
        override suspend fun prepareReclaim(leaseId: String): SlotLease {
            preparations++
            return state.value.slots.flatMap { it.snapshot.leases }.single { it.id == leaseId }
                .copy(reclaimConfirmation = SlotReclaimConfirmation("confirmation", now, now + 15.minutes))
        }
        override suspend fun confirmReclaim(leaseId: String, confirmationId: String): SlotLease {
            assertEquals("confirmation", confirmationId); confirmations++
            return state.value.slots.flatMap { it.snapshot.leases }.single { it.id == leaseId }.copy(releasedAt = now)
        }
        override suspend fun cancelRequest(requestId: String): SlotRequest {
            val request = state.value.automaticRequests.single { it.id == requestId }
            cancelled += requestId
            state.value = state.value.copy(automaticRequests = state.value.automaticRequests.filterNot { it.id == requestId })
            return request.copy(state = SlotRequest.State.CANCELLED)
        }
    }
}
