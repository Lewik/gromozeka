package com.gromozeka.presentation.ui.notifications

import androidx.compose.runtime.*
import com.gromozeka.domain.model.*
import com.gromozeka.domain.service.UserNotificationService
import com.gromozeka.presentation.services.UserNotificationSink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect

/** UI-dispatcher-confined state, owned by the authenticated app rather than a conversation tab. */
@Stable
class UserNotificationsState(private val owner: User.Id) {
    var summary by mutableStateOf<UserNotificationSummary?>(null)
        private set
    var summaryFailed by mutableStateOf(false)
        private set
    var observationRetry by mutableIntStateOf(0)
        private set
    var visible by mutableStateOf(false)
        private set
    var items by mutableStateOf<List<UserNotification>>(emptyList())
        private set
    var nextCursor by mutableStateOf<UserNotificationCursor?>(null)
        private set
    var unreadOnly by mutableStateOf(false)
        private set
    var loading by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var problem by mutableStateOf<Problem?>(null)
        private set

    enum class Problem { LOAD, ACTION, CONVERSATION }

    private var loadToken: Any? = null
    private var hasBaseline = false
    private var newestSeen: UserNotificationCursor? = null

    suspend fun observe(service: UserNotificationService, sink: UserNotificationSink) {
        summaryFailed = false
        try {
            service.observeSummary().collect { value ->
                currentCoroutineContext().ensureActive()
                val latest = value.latest
                require(value.unreadCount >= 0 && (latest == null || latest.userId == owner)) { "Invalid notification summary owner" }
                val cursor = latest?.let { UserNotificationCursor(it.createdAt, it.id) }
                if (latest != null && cursor != null && (newestSeen == null || after(cursor, newestSeen!!))) {
                    if (hasBaseline && latest.readAt == null) sink.show(latest)
                    newestSeen = cursor
                }
                hasBaseline = true
                summary = value
                summaryFailed = false
                if (visible) load(service, more = false)
            }
            currentCoroutineContext().ensureActive()
            summaryFailed = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            summaryFailed = true
        }
    }

    suspend fun open(service: UserNotificationService) {
        visible = true
        load(service, more = false)
    }

    fun close() {
        visible = false
        loadToken = null
        loading = false
    }

    suspend fun refresh(service: UserNotificationService) {
        if (summaryFailed) observationRetry++
        load(service, more = false)
    }

    suspend fun filter(service: UserNotificationService, onlyUnread: Boolean) {
        if (unreadOnly == onlyUnread) return
        unreadOnly = onlyUnread
        items = emptyList()
        nextCursor = null
        load(service, more = false)
    }

    suspend fun loadMore(service: UserNotificationService) {
        if (!loading && nextCursor != null) load(service, more = true)
    }

    private suspend fun load(service: UserNotificationService, more: Boolean) {
        val cursor = if (more) nextCursor ?: return else null
        val token = Any()
        loadToken = token
        loading = true
        problem = null
        try {
            val page = service.list(cursor, 50, unreadOnly)
            currentCoroutineContext().ensureActive()
            require(page.items.size <= 50 && page.items.all { it.userId == owner } && page.items.map { it.id }.distinct().size == page.items.size) {
                "Invalid notification page"
            }
            if (loadToken === token) {
                items = if (more) (items + page.items).distinctBy { it.id } else page.items
                nextCursor = page.nextCursor
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            if (loadToken === token) problem = Problem.LOAD
        } finally {
            if (loadToken === token) { loadToken = null; loading = false }
        }
    }

    suspend fun setRead(service: UserNotificationService, notification: UserNotification, read: Boolean) = action(service) {
        val result = service.setRead(notification.id, read)
        check(result.id == notification.id && result.userId == owner) { "Notification mutation returned another owner" }
    }

    suspend fun markAllRead(service: UserNotificationService) = action(service) { service.markAllRead() }

    suspend fun delete(service: UserNotificationService, id: UserNotification.Id) = action(service) { service.delete(id) }

    suspend fun follow(service: UserNotificationService, notification: UserNotification, open: suspend (Conversation.Id) -> Unit) {
        val target = notification.conversationId ?: return
        if (busy) return
        busy = true
        problem = null
        var opened = false
        try {
            open(target)
            opened = true
            val result = service.setRead(notification.id, true)
            check(result.userId == owner && result.id == notification.id)
            close()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            problem = if (opened) Problem.ACTION else Problem.CONVERSATION
        } finally { busy = false }
    }

    private suspend fun action(service: UserNotificationService, mutation: suspend () -> Unit) {
        if (busy) return
        busy = true
        problem = null
        try {
            mutation()
            currentCoroutineContext().ensureActive()
            if (visible) load(service, more = false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            problem = Problem.ACTION
        } finally { busy = false }
    }

    private fun after(a: UserNotificationCursor, b: UserNotificationCursor): Boolean =
        a.createdAt > b.createdAt || (a.createdAt == b.createdAt && a.id.value > b.id.value)
}
