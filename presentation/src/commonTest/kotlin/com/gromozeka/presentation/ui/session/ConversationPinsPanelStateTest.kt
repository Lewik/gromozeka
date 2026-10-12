package com.gromozeka.presentation.ui.session

import androidx.compose.ui.text.input.TextFieldValue
import com.gromozeka.domain.model.*
import com.gromozeka.domain.service.ConversationPinService
import com.gromozeka.domain.service.ProjectAccessDeniedException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationPinsPanelStateTest {
    private val a = Conversation.Id("pins-a")
    private val b = Conversation.Id("pins-b")

    @Test fun draftsAreIndependentAndBeginCreateNeverReplacesAnExistingDraft() {
        val state = ConversationPinsPanelState()
        val source = ConversationPinSource(b, Conversation.Message.Id("private-source"))
        assertTrue(state.beginCreate(a, " first ", source))
        val original = state.snapshot(a).draft!!
        assertFalse(state.beginCreate(a, "replacement"))
        assertTrue(state.beginCreate(b, "second"))
        assertEquals(original, state.snapshot(a).draft)
        assertEquals("second", state.snapshot(b).draft!!.value.text)
        assertNotEquals(original.creationKey, state.snapshot(b).draft!!.creationKey)
        assertEquals(source, original.source)
        state.discard(a)
        assertTrue(state.beginCreate(a, "new intent"))
        assertNotEquals(original.creationKey, state.snapshot(a).draft!!.creationKey)
    }

    @Test fun exactWhitespaceUnicodeAndInertProvenanceReachCreateAndUpdate() = runTest {
        val state = ConversationPinsPanelState(); val service = FakeConversationPinService()
        val text = " \t\n漢字 🙂 e\u0301\r\n "
        val source = ConversationPinSource(b, Conversation.Message.Id("never-dereferenced"))
        state.beginCreate(a, text, source)
        val key = state.snapshot(a).draft!!.creationKey
        state.save(a, service)
        assertEquals(FakeConversationPinService.Create(a, text, source, key), service.creates.single())
        assertEquals(text, service.pins(a).single().text); assertFalse(state.hasDraft(a))
        assertTrue(service.gets.isEmpty()); assertEquals(0, service.listCalls)
        val pin = service.pins(a).single()
        state.beginEdit(a, pin); state.edit(a, TextFieldValue(text + "\nmore  "))
        state.save(a, service)
        assertEquals(FakeConversationPinService.Update(a, pin.id, pin.revision, text + "\nmore  "), service.updates.single())
        assertEquals(source, service.pins(a).single().source)
        assertEquals(pin.revision + 1, service.pins(a).single().revision)
    }

    @Test fun sharedValidationDoesNotTruncateOrTrimInputAndNoWriteIsAttempted() = runTest {
        val state = ConversationPinsPanelState(); val service = FakeConversationPinService()
        for ((text, problem) in listOf(" \n\t" to PinProblem.INVALID_TEXT, "x".repeat(ConversationPinLimits.MAX_TEXT_LENGTH + 1) to PinProblem.LIMIT)) {
            state.discard(a); state.beginCreate(a, text); state.save(a, service)
            assertEquals(text, state.snapshot(a).draft!!.value.text)
            assertEquals(problem, state.snapshot(a).draft!!.problem)
        }
        assertTrue(service.creates.isEmpty())
    }

    @Test fun failedOrUnknownCreateKeepsStableKeyAndOnlyExplicitRetryWrites() = runTest {
        val state = ConversationPinsPanelState(); val service = FakeConversationPinService()
        state.beginCreate(a, " exact ")
        val key = state.snapshot(a).draft!!.creationKey
        service.failAfterCreate = IllegalStateException("sensitive storage detail")
        state.save(a, service)
        assertEquals(1, service.creates.size); assertEquals(1, service.pins(a).size)
        assertEquals(PinProblem.WRITE_FAILED, state.snapshot(a).draft!!.problem)
        runCurrent(); assertEquals(1, service.creates.size)
        service.failAfterCreate = null
        state.save(a, service)
        assertEquals(listOf(key, key), service.creates.map { it.key })
        assertEquals(1, service.pins(a).size); assertFalse(state.hasDraft(a))
    }

    @Test fun changingAnUnknownCreateDoesNotSilentlyRotateItsKeyToBypassConflict() = runTest {
        val state = ConversationPinsPanelState(); val service = FakeConversationPinService()
        state.beginCreate(a, "first"); val key = state.snapshot(a).draft!!.creationKey
        service.failAfterCreate = ConversationPinOperationFailedException()
        state.save(a, service); service.failAfterCreate = null
        state.edit(a, TextFieldValue("changed")); state.save(a, service)
        assertEquals(listOf(key, key), service.creates.map { it.key })
        assertEquals(PinProblem.CREATE_CONFLICT, state.snapshot(a).draft!!.problem)
        assertEquals("changed", state.snapshot(a).draft!!.value.text)
        assertEquals("first", service.pins(a).single().text)
    }

    @Test fun doubleClicksAndConcurrentActionsCannotStartOverlappingWrites() = runTest {
        val state = ConversationPinsPanelState(); val service = FakeConversationPinService()
        val pin = pinFixture(); service.emit(a, listOf(pin)); state.requestDelete(a, pin)
        state.beginCreate(a, "new")
        val gate = CompletableDeferred<Unit>(); service.createGate = gate
        val saving = launch { state.save(a, service) }; runCurrent()
        state.save(a, service); state.confirmDelete(a, service)
        assertEquals(1, service.creates.size); assertTrue(service.deletes.isEmpty())
        gate.complete(Unit); saving.join()
        assertNull(state.snapshot(a).operation)
    }

    @Test fun typingDuringCreateIsKeptAndSubsequentSaveUpdatesTheCreatedPin() = runTest {
        val state = ConversationPinsPanelState(); val service = FakeConversationPinService()
        service.createGate = CompletableDeferred()
        state.beginCreate(a, "submitted")
        val saving = launch { state.save(a, service) }; runCurrent()
        state.edit(a, TextFieldValue("submitted and still typing"))
        service.createGate!!.complete(Unit); saving.join()
        val created = service.pins(a).single()
        assertEquals("submitted", created.text)
        assertEquals(created.id, state.snapshot(a).draft!!.pinId)
        assertEquals(created.revision, state.snapshot(a).draft!!.expectedRevision)
        assertEquals("submitted and still typing", state.snapshot(a).draft!!.value.text)
        state.save(a, service)
        assertEquals(1, service.creates.size); assertEquals(1, service.updates.size)
        assertEquals("submitted and still typing", service.pins(a).single().text)
    }

    @Test fun lateSuccessOrFailureCannotReplaceNewDraftOrAnotherConversation() = runTest {
        for (failure in listOf(null, ConversationPinOperationFailedException())) {
            val state = ConversationPinsPanelState(); val service = FakeConversationPinService()
            service.createGate = CompletableDeferred(); service.createFailure = failure
            state.beginCreate(a, "old"); val saving = launch { state.save(a, service) }; runCurrent()
            state.discard(a); state.beginCreate(a, "new")
            state.beginCreate(b, "another conversation")
            val newDraft = state.snapshot(a).draft; val otherDraft = state.snapshot(b).draft
            service.createGate!!.complete(Unit); saving.join()
            assertEquals(newDraft, state.snapshot(a).draft); assertEquals(otherDraft, state.snapshot(b).draft)
            assertNull(state.snapshot(a).operation); assertNull(state.snapshot(b).operation)
        }
    }

    @Test fun liveSnapshotsAndCasConflictKeepDraftAndRevisionUntilExplicitReload() = runTest {
        val state = ConversationPinsPanelState(); val service = FakeConversationPinService()
        val original = pinFixture(); service.emit(a, listOf(original))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { state.observe(a, service, Any()) }
        state.beginEdit(a, original); state.edit(a, TextFieldValue("local draft"))
        val newer = original.copy(text = "remote change", revision = 2)
        service.emit(a, listOf(newer)); runCurrent()
        assertEquals("local draft", state.snapshot(a).draft!!.value.text)
        assertEquals(1L, state.snapshot(a).draft!!.expectedRevision)
        state.save(a, service); runCurrent()
        assertEquals(1, service.updates.size)
        assertEquals(PinProblem.CONFLICT, state.snapshot(a).draft!!.problem)
        assertEquals("local draft", state.snapshot(a).draft!!.value.text)
        assertTrue(service.gets.isEmpty())
        state.reloadDraft(a, service)
        assertEquals("remote change", state.snapshot(a).draft!!.value.text)
        assertEquals(2L, state.snapshot(a).draft!!.expectedRevision)
        assertEquals(1, service.gets.size)
        state.edit(a, TextFieldValue("explicit new edit")); state.save(a, service)
        assertEquals(2L, service.updates.last().revision)
        assertEquals("explicit new edit", service.pins(a).single().text)
    }

    @Test fun missingPinOrReadFailureNeverErasesEditableText() = runTest {
        val state = ConversationPinsPanelState(); val service = FakeConversationPinService()
        state.beginEdit(a, pinFixture()); state.edit(a, TextFieldValue("keep me"))
        state.reloadDraft(a, service)
        assertEquals(PinProblem.NOT_FOUND, state.snapshot(a).draft!!.problem)
        service.getFailure = IllegalStateException("private response")
        state.reloadDraft(a, service)
        assertEquals(PinProblem.READ_FAILED, state.snapshot(a).draft!!.problem)
        state.save(a, service)
        assertEquals(PinProblem.NOT_FOUND, state.snapshot(a).draft!!.problem)
        assertEquals("keep me", state.snapshot(a).draft!!.value.text)
    }

    @Test fun delayedReloadCannotClobberLaterTypingOrAReplacementDraft() = runTest {
        val state = ConversationPinsPanelState(); val service = FakeConversationPinService()
        val original = pinFixture(); service.emit(a, listOf(original.copy(text = "server", revision = 2)))
        state.beginEdit(a, original)
        service.getGate = CompletableDeferred()
        val loading = launch { state.reloadDraft(a, service) }; runCurrent()
        state.edit(a, TextFieldValue("typed while loading"))
        service.getGate!!.complete(Unit); loading.join()
        assertEquals("typed while loading", state.snapshot(a).draft!!.value.text)
        assertEquals(1L, state.snapshot(a).draft!!.expectedRevision)
        assertEquals(PinProblem.RELOAD_EDITED, state.snapshot(a).draft!!.problem)
        service.getGate = CompletableDeferred()
        val second = launch { state.reloadDraft(a, service) }; runCurrent()
        state.discard(a); state.beginCreate(a, "new draft")
        val newest = state.snapshot(a).draft
        service.getGate!!.complete(Unit); second.join()
        assertEquals(newest, state.snapshot(a).draft)
    }

    @Test fun deleteUsesConfirmedObservedRevisionAndNeverRetriesAConflict() = runTest {
        val state = ConversationPinsPanelState(); val service = FakeConversationPinService()
        val original = pinFixture(); service.emit(a, listOf(original))
        state.requestDelete(a, original)
        service.emit(a, listOf(original.copy(revision = 2)))
        state.confirmDelete(a, service); runCurrent()
        assertEquals(listOf(FakeConversationPinService.Delete(a, original.id, 1)), service.deletes)
        assertEquals(PinProblem.CONFLICT, state.snapshot(a).deleteProblem)
        assertEquals(1, service.pins(a).size)
        state.dismissDelete(a); state.requestDelete(a, service.pins(a).single())
        state.confirmDelete(a, service)
        assertEquals(2L, service.deletes.last().revision); assertTrue(service.pins(a).isEmpty())
        assertNull(state.snapshot(a).deleteTarget)
    }

    @Test fun deletionKeepsAnExistingDraftAndFailedDeleteHasNoImplicitEffect() = runTest {
        val state = ConversationPinsPanelState(); val service = FakeConversationPinService()
        val pin = pinFixture(); service.emit(a, listOf(pin)); state.beginEdit(a, pin)
        state.edit(a, TextFieldValue("still editable")); state.requestDelete(a, pin)
        service.deleteFailure = ConversationPinOperationFailedException()
        state.confirmDelete(a, service)
        assertEquals(1, service.pins(a).size); assertEquals(PinProblem.WRITE_FAILED, state.snapshot(a).deleteProblem)
        service.deleteFailure = null; state.confirmDelete(a, service)
        assertTrue(service.pins(a).isEmpty())
        assertEquals("still editable", state.snapshot(a).draft!!.value.text)
        assertEquals(PinProblem.NOT_FOUND, state.snapshot(a).draft!!.problem)
    }

    @Test fun cancellationIsRethrownAndPreservesDraftAndCreationKey() = runTest {
        val state = ConversationPinsPanelState(); val service = FakeConversationPinService()
        state.beginCreate(a, "keep cancelled draft"); val draft = state.snapshot(a).draft
        service.createFailure = CancellationException("cancelled")
        assertFailsWith<CancellationException> { state.save(a, service) }
        assertEquals(draft, state.snapshot(a).draft); assertNull(state.snapshot(a).operation)
        service.readFailure = CancellationException("cancelled read")
        assertFailsWith<CancellationException> { state.observe(a, service, Any()) }
        assertNull(state.snapshot(a).loadProblem)
    }

    @Test fun lateNonCooperativeReadIsFencedByVisibilityDisposal() = runTest {
        val state = ConversationPinsPanelState(); val gate = CompletableDeferred<Unit>()
        val service = object : ConversationPinService by FakeConversationPinService() {
            override fun observe(conversationId: Conversation.Id): Flow<List<ConversationPin>> = flow {
                gate.await(); emit(listOf(pinFixture()))
            }
        }
        val token = Any(); val loading = launch { state.observe(a, service, token) }; runCurrent()
        state.stopObserving(a, token)
        state.beginCreate(a, "not a list echo")
        gate.complete(Unit); loading.join()
        assertTrue(state.snapshot(a).pins.isEmpty())
        assertEquals("not a list echo", state.snapshot(a).draft!!.value.text)
    }

    @Test fun observerFailureIsNotEmptyAndRetryAndSortingDoNotTouchDraft() = runTest {
        val state = ConversationPinsPanelState(); val service = FakeConversationPinService()
        state.beginCreate(a, "draft")
        service.readFailure = IllegalStateException("private server body")
        state.observe(a, service, Any())
        assertEquals(PinsLoad.FAILED, state.snapshot(a).load)
        assertEquals(PinProblem.READ_FAILED, state.snapshot(a).loadProblem)
        service.readFailure = null
        service.emit(a, listOf(pinFixture(ordinal = 2), pinFixture(ordinal = 1)))
        state.retry(a)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { state.observe(a, service, Any()) }
        assertEquals(listOf(1L, 2L), state.snapshot(a).pins.map { it.ordinal })
        assertEquals("draft", state.snapshot(a).draft!!.value.text)
        assertEquals(PinsLoad.READY, state.snapshot(a).load)
    }

    @Test fun invalidCatalogIsReportedInsteadOfTruncatingItOrMixingConversations() = runTest {
        for (pins in listOf(List(101) { pinFixture(ordinal = it.toLong()) }, listOf(pinFixture(b)))) {
            val state = ConversationPinsPanelState(); val service = FakeConversationPinService()
            service.emit(a, pins); state.observe(a, service, Any())
            assertEquals(PinsLoad.FAILED, state.snapshot(a).load)
            assertTrue(state.snapshot(a).pins.isEmpty())
        }
    }

    @Test fun contentFreeFailuresNeverStoreThrowableMessagesOrSourceContent() = runTest {
        val state = ConversationPinsPanelState(); val service = FakeConversationPinService()
        state.beginCreate(a, "user text")
        val draft = state.snapshot(a).draft
        state.sourceLoadFailed(a)
        assertTrue(state.snapshot(a).sourceFailed); assertEquals(draft, state.snapshot(a).draft)
        for ((failure, expected) in listOf(
            IllegalArgumentException("malformed UTF16 or private body") to PinProblem.INVALID_TEXT,
            ConversationPinLimitException() to PinProblem.LIMIT,
            ProjectAccessDeniedException() to PinProblem.ACCESS_DENIED,
            IllegalStateException("server body and private source") to PinProblem.WRITE_FAILED,
        )) {
            service.createFailure = failure; state.save(a, service)
            assertEquals(expected, state.snapshot(a).draft!!.problem)
            assertEquals("user text", state.snapshot(a).draft!!.value.text)
            assertTrue(state.snapshot(a).sourceFailed)
        }
    }
    @Test fun obsoleteUiActionsCannotSaveANewDraftOrDeleteANewConfirmationTarget() = runTest {
        val state = ConversationPinsPanelState(); val service = FakeConversationPinService()
        state.beginCreate(a, "old"); val old = state.snapshot(a).draft!!
        state.discard(a); state.beginCreate(a, "new")
        state.save(a, service, expectedDraftId = old.id, actionVersion = 0)
        assertTrue(service.creates.isEmpty()); assertEquals("new", state.snapshot(a).draft!!.value.text)
        val first = pinFixture(); val second = pinFixture(ordinal = 2)
        service.emit(a, listOf(first, second))
        state.requestDelete(a, first); state.dismissDelete(a); state.requestDelete(a, second)
        state.confirmDelete(a, service, expectedTarget = first, actionVersion = 0)
        assertTrue(service.deletes.isEmpty()); assertEquals(second, state.snapshot(a).deleteTarget)
    }

    @Test fun repeatedCallbacksFromOneFrameDoNotRetryEvenAnImmediateFailure() = runTest {
        val state = ConversationPinsPanelState(); val service = FakeConversationPinService()
        service.createFailure = ConversationPinOperationFailedException()
        state.beginCreate(a, "draft"); val draft = state.snapshot(a).draft!!; val frame = state.snapshot(a).actions
        state.save(a, service, draft.id, frame); state.save(a, service, draft.id, frame)
        assertEquals(1, service.creates.size)
        state.save(a, service, draft.id, state.snapshot(a).actions)
        assertEquals(2, service.creates.size)
        assertEquals(1, service.creates.map { it.key }.distinct().size)
    }

    @Test fun typingDuringUpdateIsNotErasedByItsAcknowledgment() = runTest {
        val state = ConversationPinsPanelState(); val service = FakeConversationPinService()
        val pin = pinFixture(); service.emit(a, listOf(pin)); state.beginEdit(a, pin)
        state.edit(a, TextFieldValue("sent")); service.updateGate = CompletableDeferred()
        val saving = launch { state.save(a, service) }; runCurrent()
        state.edit(a, TextFieldValue("later local edit"))
        service.updateGate!!.complete(Unit); saving.join()
        assertEquals("sent", service.pins(a).single().text)
        assertEquals("later local edit", state.snapshot(a).draft!!.value.text)
        assertEquals(2L, state.snapshot(a).draft!!.expectedRevision)
        assertEquals(1, service.updates.size)
    }

    @Test fun cancelledNonCooperativeWriteCannotConsumeTheDraftOnLateSuccess() = runTest {
        val state = ConversationPinsPanelState(); val service = FakeConversationPinService()
        service.nonCooperativeCreate = true; service.createGate = CompletableDeferred()
        state.beginCreate(a, "keep even if outcome is unknown"); val draft = state.snapshot(a).draft
        val saving = launch { state.save(a, service) }; runCurrent()
        saving.cancel(); service.createGate!!.complete(Unit); saving.join()
        assertTrue(saving.isCancelled); assertEquals(draft, state.snapshot(a).draft)
        assertNull(state.snapshot(a).operation); assertEquals(1, service.creates.size)
    }

    @Test fun olderObservationCannotReplaceNewerSnapshotOrClearItsSubscriptionToken() = runTest {
        val state = ConversationPinsPanelState(); val gate = CompletableDeferred<Unit>()
        val old = object : ConversationPinService by FakeConversationPinService() {
            override fun observe(conversationId: Conversation.Id) = flow { gate.await(); emit(listOf(pinFixture(text = "old"))) }
        }
        val oldRead = launch { state.observe(a, old, Any()) }; runCurrent()
        val fresh = FakeConversationPinService(); fresh.emit(a, listOf(pinFixture(text = "new")))
        val token = Any()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { state.observe(a, fresh, token) }
        gate.complete(Unit); oldRead.join()
        assertEquals("new", state.snapshot(a).pins.single().text)
        assertSame(token, state.snapshot(a).observation)
    }

    @Test fun completedReadWithoutSnapshotIsFailureNotAnEmptyCatalog() = runTest {
        val state = ConversationPinsPanelState()
        val service = object : ConversationPinService by FakeConversationPinService() {
            override fun observe(conversationId: Conversation.Id) = emptyFlow<List<ConversationPin>>()
        }
        state.observe(a, service, Any())
        assertEquals(PinsLoad.FAILED, state.snapshot(a).load)
        assertEquals(PinProblem.READ_FAILED, state.snapshot(a).loadProblem)
    }

    @Test fun newerObservedRevisionWinsOverAnOlderMutationAcknowledgmentOrReload() = runTest {
        val state = ConversationPinsPanelState(); val service = FakeConversationPinService()
        val pin = pinFixture(); service.emit(a, listOf(pin))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { state.observe(a, service, Any()) }
        state.beginEdit(a, pin); state.edit(a, TextFieldValue("sent draft"))
        service.afterUpdateGate = CompletableDeferred()
        val saving = launch { state.save(a, service) }; runCurrent()
        val remote = pin.copy(text = "newer remote", revision = 3)
        service.emit(a, listOf(remote)); runCurrent()
        service.afterUpdateGate!!.complete(Unit); saving.join()
        assertEquals("sent draft", state.snapshot(a).draft!!.value.text)
        assertEquals(PinProblem.CONFLICT, state.snapshot(a).draft!!.problem)
        assertEquals(remote, state.snapshot(a).pins.single())
        service.getGate = CompletableDeferred()
        val reloading = launch { state.reloadDraft(a, service) }; runCurrent()
        service.emit(a, listOf(remote.copy(text = "even newer", revision = 4))); runCurrent()
        service.getGate!!.complete(Unit); reloading.join()
        assertEquals("sent draft", state.snapshot(a).draft!!.value.text)
        assertEquals(PinProblem.CONFLICT, state.snapshot(a).draft!!.problem)
        assertEquals(1, service.updates.size)
    }

}
