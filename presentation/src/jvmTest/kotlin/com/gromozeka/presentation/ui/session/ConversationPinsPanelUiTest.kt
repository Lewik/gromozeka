package com.gromozeka.presentation.ui.session

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import com.gromozeka.domain.model.*
import com.gromozeka.presentation.ui.GromozekaTheme
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.*

/** Compose + FakeConversationPinService effects only, not a Server/SQL/device end-to-end test. */
class ConversationPinsPanelUiTest {
    private val a = Conversation.Id("pins-a")
    private val b = Conversation.Id("pins-b")

    @Test fun desktopAndTouchLayoutsCloseReopenAndKeepTheirOnlyEditableDraft() {
        for (touch in listOf(false, true)) runComposeUiTest {
            val service = FakeConversationPinService(); val state = ConversationPinsPanelState()
            val visible = mutableStateOf(false); var closes = 0
            setContent { GromozekaTheme { Box(Modifier.size(700.dp, 640.dp)) {
                ConversationPinsPanel(visible.value, a, service, state, { closes++; visible.value = false }, fullScreen = touch, slideFromRight = touch)
            } } }
            onNodeWithTag("pins-panel").assertDoesNotExist()
            runOnIdle {
                service.emit(a, listOf(pinFixture()))
                assertTrue(service.observations.isEmpty())
            }
            onNodeWithTag("pins-panel").assertDoesNotExist() // External changes cannot open the panel.
            runOnIdle { visible.value = true }
            onNodeWithTag("pins-panel").assertWidthIsEqualTo(if (touch) 700.dp else 400.dp)
            onNodeWithTag("pins-add").performClick()
            onAllNodes(hasSetTextAction()).assertCountEquals(1)
            onNodeWithTag("pins-text").performTextReplacement(" unsaved 🙂 \n")
            val creationKey = runOnIdle { state.snapshot(a).draft!!.creationKey }
            if (touch) onNodeWithTag("pins-close").performTouchInput { click() } else onNodeWithTag("pins-close").performClick()
            onNodeWithTag("pins-panel").assertDoesNotExist()
            runOnIdle {
                assertEquals(1, closes); assertEquals(0, service.activeObservers)
                assertEquals(" unsaved 🙂 \n", state.snapshot(a).draft!!.value.text)
                visible.value = true
            }
            onNodeWithTag("pins-text").assertTextEquals(" unsaved 🙂 \n")
            onAllNodes(hasSetTextAction()).assertCountEquals(1)
            runOnIdle {
                assertEquals(creationKey, state.snapshot(a).draft!!.creationKey)
                assertEquals(2, service.observations.size)
                assertTrue(service.creates.isEmpty())
            }
        }
    }

    @Test fun loadingFailureIsNotEmptyAndExplicitRetryGetsAFreshSnapshot() = runComposeUiTest {
        val service = FakeConversationPinService(); val state = ConversationPinsPanelState()
        val gate = CompletableDeferred<Unit>(); service.observeGate = gate
        service.readFailure = IllegalStateException("PRIVATE-SERVER-BODY")
        setContent { GromozekaTheme { ConversationPinsPanel(true, a, service, state, {}) } }
        onNodeWithTag("pins-loading").assertExists()
        onNodeWithTag("pins-empty").assertDoesNotExist()
        runOnIdle { gate.complete(Unit) }
        onNodeWithTag("pins-load-error").assertExists()
        onNodeWithTag("pins-empty").assertDoesNotExist()
        onNodeWithText("PRIVATE-SERVER-BODY", substring = true).assertDoesNotExist()
        runOnIdle { service.readFailure = null }
        onNodeWithTag("pins-retry").performClick()
        onNodeWithTag("pins-empty").assertExists()
        onNodeWithTag("pins-load-error").assertDoesNotExist()
        runOnIdle { assertEquals(2, service.observations.size); assertEquals(0, service.listCalls) }
    }

    @Test fun actualCreateUpdateAndConfirmedDeleteHaveOnlyOneEditableTextField() = runComposeUiTest {
        val service = FakeConversationPinService(); val state = ConversationPinsPanelState()
        setContent { GromozekaTheme { ConversationPinsPanel(true, a, service, state, {}) } }
        onNodeWithTag("pins-empty").assertExists()
        onAllNodes(hasSetTextAction()).assertCountEquals(0)
        onNodeWithTag("pins-add").performClick()
        onAllNodes(hasSetTextAction()).assertCountEquals(1)
        val exact = " \n漢字 🙂 e\u0301\t "
        onNodeWithTag("pins-text").performTextReplacement(exact)
        onNodeWithTag("pins-save").performClick()
        onNodeWithTag("pin-synthetic-pin-1").assertExists()
        runOnIdle {
            assertEquals(exact, service.creates.single().text)
            assertEquals(exact, service.pins(a).single().text)
            assertNotNull(service.creates.single().key)
        }
        onNodeWithTag("pin-edit-synthetic-pin-1").performClick()
        onAllNodes(hasSetTextAction()).assertCountEquals(1)
        onNodeWithTag("pins-text").performTextReplacement("  edited text  ")
        onNodeWithTag("pins-save").performClick()
        runOnIdle {
            assertEquals(1L, service.updates.single().revision)
            assertEquals("  edited text  ", service.pins(a).single().text)
            assertEquals(2L, service.pins(a).single().revision)
        }
        onNodeWithTag("pin-delete-synthetic-pin-1").performClick()
        onNodeWithTag("pins-delete-dialog").assertExists()
        onAllNodes(hasSetTextAction()).assertCountEquals(0)
        runOnIdle { assertTrue(service.deletes.isEmpty()) }
        onNodeWithTag("pins-delete-cancel").performClick()
        onNodeWithTag("pin-synthetic-pin-1").assertExists()
        onNodeWithTag("pin-delete-synthetic-pin-1").performClick()
        onNodeWithTag("pins-delete-confirm").performClick()
        onNodeWithTag("pins-empty").assertExists()
        runOnIdle {
            assertEquals(FakeConversationPinService.Delete(a, ConversationPin.Id("synthetic-pin-1"), 2), service.deletes.single())
            assertTrue(service.pins(a).isEmpty())
        }
    }

    @Test fun sourceSnapshotHasNoEditableOrLinkedMetadataAndSourceFailureIsContentFree() = runComposeUiTest {
        val service = FakeConversationPinService(); val state = ConversationPinsPanelState()
        val source = ConversationPinSource(Conversation.Id("PRIVATE-SOURCE-CONVERSATION"), Conversation.Message.Id("PRIVATE-SOURCE-MESSAGE"))
        val exact = "  snapshot\r\n🙂\t "
        state.beginCreate(a, exact, source)
        setContent { GromozekaTheme { ConversationPinsPanel(true, a, service, state, {}) } }
        onAllNodes(hasSetTextAction()).assertCountEquals(1)
        onNodeWithText(source.conversationId.value, substring = true).assertDoesNotExist()
        onNodeWithText(source.messageId.value, substring = true).assertDoesNotExist()
        runOnIdle { state.sourceLoadFailed(a) }
        onNodeWithTag("pins-source-error").assertExists()
        onNodeWithTag("pins-save").performClick()
        runOnIdle {
            assertEquals(source, service.creates.single().source)
            assertEquals(exact, service.creates.single().text)
            assertTrue(service.gets.isEmpty())
        }
        onNodeWithText(source.conversationId.value, substring = true).assertDoesNotExist()
    }

    @Test fun observerChangesConflictAndDeletionKeepDraftUntilExplicitReload() = runComposeUiTest {
        val service = FakeConversationPinService(); val state = ConversationPinsPanelState()
        val pin = pinFixture(); service.emit(a, listOf(pin))
        setContent { GromozekaTheme { ConversationPinsPanel(true, a, service, state, {}) } }
        onNodeWithTag("pin-edit-pin-1").performClick()
        onNodeWithTag("pins-text").performTextReplacement("unsaved draft")
        runOnIdle { service.emit(a, listOf(pin.copy(text = "remote text", revision = 2))) }
        onNodeWithTag("pins-remote-change").assertExists()
        onNodeWithTag("pins-text").assertTextEquals("unsaved draft")
        onNodeWithTag("pins-save").performClick()
        onNodeWithTag("pins-draft-error").assertExists()
        onNodeWithTag("pins-text").assertTextEquals("unsaved draft")
        runOnIdle { assertEquals(1, service.updates.size); assertTrue(service.gets.isEmpty()) }
        onNodeWithTag("pins-reload").performClick()
        onNodeWithTag("pins-text").assertTextEquals("remote text")
        runOnIdle { assertEquals(2L, state.snapshot(a).draft!!.expectedRevision) }
        onNodeWithTag("pins-text").performTextReplacement("keep deleted pin text")
        runOnIdle { service.emit(a, emptyList()) }
        onNodeWithTag("pins-save").performClick()
        onNodeWithTag("pins-draft-error").assertExists()
        onNodeWithTag("pins-text").assertTextEquals("keep deleted pin text")
        runOnIdle { assertEquals(PinProblem.NOT_FOUND, state.snapshot(a).draft!!.problem); assertEquals(2, service.updates.size) }
    }

    @Test fun duplicateSaveCallbacksDoNotDuplicateWritesAndTypingContinuesSafely() = runComposeUiTest {
        val service = FakeConversationPinService(); val state = ConversationPinsPanelState()
        val gate = CompletableDeferred<Unit>(); service.createGate = gate
        state.beginCreate(a, "submitted")
        setContent { GromozekaTheme { ConversationPinsPanel(true, a, service, state, {}) } }
        onNodeWithTag("pins-save").performSemanticsAction(SemanticsActions.OnClick) { click -> click(); click() }
        onNodeWithTag("pins-save").assertIsNotEnabled()
        runOnIdle { assertEquals(1, service.creates.size) }
        onNodeWithTag("pins-text").performTextReplacement("typed after submitting")
        runOnIdle { gate.complete(Unit) }
        onNodeWithTag("pins-text").assertTextEquals("typed after submitting")
        runOnIdle {
            assertEquals("submitted", service.pins(a).single().text)
            assertEquals(service.pins(a).single().id, state.snapshot(a).draft!!.pinId)
        }
        onNodeWithTag("pins-save").performClick()
        runOnIdle {
            assertEquals(1, service.creates.size); assertEquals(1, service.updates.size)
            assertEquals("typed after submitting", service.pins(a).single().text)
        }
    }

    @Test fun explicitRetryUsesSameCreationKeyAndDoesNotExposeAnExceptionMessage() = runComposeUiTest {
        val service = FakeConversationPinService(); val state = ConversationPinsPanelState()
        service.createFailure = IllegalStateException("PRIVATE-ERROR-PAYLOAD")
        state.beginCreate(a, " retry exactly ")
        val key = state.snapshot(a).draft!!.creationKey
        setContent { GromozekaTheme { ConversationPinsPanel(true, a, service, state, {}) } }
        onNodeWithTag("pins-save").performClick()
        onNodeWithTag("pins-draft-error").assertExists()
        onNodeWithText("PRIVATE-ERROR-PAYLOAD", substring = true).assertDoesNotExist()
        onNodeWithTag("pins-text").assertTextEquals(" retry exactly ")
        runOnIdle { assertEquals(1, service.creates.size); service.createFailure = null }
        onNodeWithTag("pins-save").performClick()
        runOnIdle {
            assertEquals(listOf(key, key), service.creates.map { it.key })
            assertEquals(1, service.pins(a).size)
        }
    }

    @Test fun switchingConversationDuringSaveCannotRedirectPayloadOrClearNewDraft() = runComposeUiTest {
        val service = FakeConversationPinService(); val state = ConversationPinsPanelState()
        val selected = mutableStateOf(a); val gate = CompletableDeferred<Unit>(); service.createGate = gate
        state.beginCreate(a, "A's text")
        setContent { GromozekaTheme { ConversationPinsPanel(true, selected.value, service, state, {}) } }
        onNodeWithTag("pins-save").performClick()
        runOnIdle { state.beginCreate(b, "B's draft"); selected.value = b }
        onNodeWithTag("pins-text").assertTextEquals("B's draft")
        runOnIdle { gate.complete(Unit) }
        onNodeWithTag("pins-text").assertTextEquals("B's draft")
        runOnIdle {
            assertEquals(a, service.creates.single().conversationId)
            assertEquals("A's text", service.pins(a).single().text)
            assertTrue(service.pins(b).isEmpty()); assertTrue(state.hasDraft(b))
            assertEquals(listOf(a, b), service.observations)
        }
    }
    @Test fun fastFailureDoesNotTurnDuplicateUiCallbacksIntoAnAutomaticRetry() = runComposeUiTest {
        val service = FakeConversationPinService(); val state = ConversationPinsPanelState()
        service.createFailure = ConversationPinOperationFailedException(); state.beginCreate(a, "draft")
        setContent { GromozekaTheme { ConversationPinsPanel(true, a, service, state, {}) } }
        onNodeWithTag("pins-save").performSemanticsAction(SemanticsActions.OnClick) { click -> click(); click() }
        onNodeWithTag("pins-draft-error").assertExists()
        runOnIdle { assertEquals(1, service.creates.size) }
        onNodeWithTag("pins-save").performClick()
        runOnIdle { assertEquals(2, service.creates.size); assertEquals(1, service.creates.map { it.key }.distinct().size) }
    }

    @Test fun deleteConfirmationRetainsItsObservedRevisionUntilExplicitlySelectedAgain() = runComposeUiTest {
        val service = FakeConversationPinService(); val state = ConversationPinsPanelState()
        val pin = pinFixture(); service.emit(a, listOf(pin))
        setContent { GromozekaTheme { ConversationPinsPanel(true, a, service, state, {}) } }
        onNodeWithTag("pin-delete-pin-1").performClick()
        runOnIdle { service.emit(a, listOf(pin.copy(text = "changed", revision = 2))) }
        onNodeWithTag("pins-delete-confirm").performClick()
        onNodeWithTag("pins-delete-error").assertExists()
        runOnIdle { assertEquals(1L, service.deletes.single().revision); assertEquals(1, service.pins(a).size) }
        onNodeWithTag("pins-delete-cancel").performClick()
        onNodeWithTag("pin-delete-pin-1").performClick()
        onNodeWithTag("pins-delete-confirm").performClick()
        onNodeWithTag("pins-empty").assertExists()
        runOnIdle { assertEquals(listOf(1L, 2L), service.deletes.map { it.revision }); assertTrue(service.pins(a).isEmpty()) }
    }

}
