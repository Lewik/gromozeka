package com.gromozeka.presentation.ui.notifications

import com.gromozeka.domain.model.*
import com.gromozeka.domain.service.UserNotificationService
import com.gromozeka.presentation.services.UserNotificationSink
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import kotlin.test.*
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class UserNotificationsStateTest {
    private val owner = User.Id("owner")
    private val now = Instant.parse("2026-01-01T00:00:00Z")
    private fun item(id: String, read: Boolean = false) = UserNotification(UserNotification.Id(id), owner,
        "Title", "Message", UserNotification.Level.WARNING, now, readAt = now.takeIf { read }, conversationId = Conversation.Id("closed"))

    @Test
    fun initialHistoryIsSilentAndNewUnreadPopupDoesNotRepeatAfterReadDeleteOrReconnect() = runTest {
        val service = Fake()
        val state = UserNotificationsState(owner)
        val popups = mutableListOf<UserNotification>()
        val sink = UserNotificationSink { popups += it }
        var observer = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { state.observe(service, sink) }
        service.updates.emit(UserNotificationSummary(1, item("a")))
        assertTrue(popups.isEmpty())
        service.updates.emit(UserNotificationSummary(2, item("b")))
        service.updates.emit(UserNotificationSummary(1, item("b", read = true)))
        service.updates.emit(UserNotificationSummary(1, item("a")))
        observer.cancelAndJoin()
        observer = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { state.observe(service, sink) }
        service.updates.emit(UserNotificationSummary(2, item("b")))
        assertEquals(listOf(item("b")), popups)
        service.updates.emit(UserNotificationSummary(1, item("c").copy(userId = User.Id("other"))))
        assertTrue(state.summaryFailed)
        assertEquals(1, popups.size)
        observer.cancelAndJoin()
    }

    @Test
    fun failedWriteIsNotOptimisticallyAppliedOrAutomaticallyRepeated() = runTest {
        val service = Fake().apply { page = UserNotificationPage(listOf(item("a"))); failWrite = true }
        val state = UserNotificationsState(owner)
        state.open(service)
        state.setRead(service, item("a"), true)
        assertEquals(1, service.readCalls)
        assertNull(state.items.single().readAt)
        assertEquals(UserNotificationsState.Problem.ACTION, state.problem)
        runCurrent()
        assertEquals(1, service.readCalls)
    }

    @Test
    fun stalePageCannotReplaceNewFilterAndClosedConversationDoesNotDiscardNotification() = runTest {
        val service = Fake()
        val old = CompletableDeferred<UserNotificationPage>()
        service.load = { _, _, unread -> if (unread) UserNotificationPage(listOf(item("b"))) else old.await() }
        val state = UserNotificationsState(owner)
        val first = launch(UnconfinedTestDispatcher(testScheduler)) { state.open(service) }
        state.filter(service, true)
        old.complete(UserNotificationPage(listOf(item("a"))))
        first.join()
        assertEquals(listOf(item("b")), state.items)
        state.follow(service, item("b")) { error("conversation deleted") }
        assertTrue(state.visible)
        assertEquals(UserNotificationsState.Problem.CONVERSATION, state.problem)
        assertEquals(0, service.readCalls)
        assertEquals(listOf(item("b")), state.items)
    }

    @Test
    fun readFailureAfterNavigationDoesNotPretendNavigationFailed() = runTest {
        val service = Fake().apply { page = UserNotificationPage(listOf(item("a"))); failWrite = true }
        val state = UserNotificationsState(owner)
        state.open(service)
        var opened = false
        state.follow(service, item("a")) { opened = true }
        assertTrue(opened)
        assertEquals(UserNotificationsState.Problem.ACTION, state.problem)
        assertEquals(1, service.readCalls)
        assertTrue(state.visible)
        assertNull(state.items.single().readAt)
    }

    @Test
    fun paginationAndOwnershipValidationAreExplicit() = runTest {
        val service = Fake()
        val cursor = UserNotificationCursor(now, UserNotification.Id("b"))
        service.load = { before, limit, unread ->
            assertEquals(50, limit)
            assertFalse(unread)
            if (before == null) UserNotificationPage(listOf(item("b")), cursor)
            else { assertEquals(cursor, before); UserNotificationPage(listOf(item("a"))) }
        }
        val state = UserNotificationsState(owner)
        state.open(service)
        state.loadMore(service)
        assertEquals(listOf(item("b"), item("a")), state.items)
        assertNull(state.nextCursor)
        service.load = { _, _, _ -> UserNotificationPage(listOf(item("foreign").copy(userId = User.Id("other")))) }
        state.refresh(service)
        assertEquals(UserNotificationsState.Problem.LOAD, state.problem)
        assertEquals(listOf(item("b"), item("a")), state.items)
    }

    private inner class Fake : UserNotificationService {
        val updates = MutableSharedFlow<UserNotificationSummary>()
        var page = UserNotificationPage(emptyList())
        var load: (suspend (UserNotificationCursor?, Int, Boolean) -> UserNotificationPage)? = null
        var failWrite = false
        var readCalls = 0
        override fun observeSummary() = updates
        override suspend fun summary() = UserNotificationSummary(0)
        override suspend fun list(before: UserNotificationCursor?, limit: Int, unreadOnly: Boolean) = load?.invoke(before, limit, unreadOnly) ?: page
        override suspend fun setRead(id: UserNotification.Id, read: Boolean): UserNotification {
            readCalls++
            if (failWrite) error("write failed")
            return item(id.value, read)
        }
        override suspend fun markAllRead() = Unit
        override suspend fun delete(id: UserNotification.Id) = Unit
    }
}
