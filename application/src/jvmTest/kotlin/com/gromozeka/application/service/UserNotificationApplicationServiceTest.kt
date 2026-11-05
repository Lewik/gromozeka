package com.gromozeka.application.service

import com.gromozeka.domain.model.*
import com.gromozeka.domain.repository.IdentityRepository
import com.gromozeka.domain.repository.UserNotificationRepository
import com.gromozeka.domain.service.*
import kotlinx.coroutines.runBlocking
import org.mockito.Mockito
import kotlin.test.*
import kotlin.time.Instant

class UserNotificationApplicationServiceTest {
    private val now = Instant.parse("2026-01-01T00:00:00Z")
    private val alice = User.Id("alice")

    @Test fun inboxUsesAuthenticatedOwnerWithoutRequiringAiAndDeniesInactiveAccounts() = runBlocking {
        val repository = Mockito.mock(UserNotificationRepository::class.java)
        val identities = Mockito.mock(IdentityRepository::class.java)
        val user = User(alice, displayName = "Alice", status = User.Status.ACTIVE, aiAllowed = false, createdAt = now, updatedAt = now)
        Mockito.`when`(identities.findUserById(alice)).thenReturn(user)
        Mockito.`when`(repository.summary(alice)).thenReturn(UserNotificationSummary(0))
        Mockito.`when`(repository.list(alice, null, 50, true)).thenReturn(UserNotificationPage(emptyList()))
        val service = UserNotificationApplicationService(repository, identities, DeclarativeStateChangePublisher {})
        assertEquals(0L, service.summary(alice).unreadCount)
        assertTrue(service.list(alice, null, 50, true).items.isEmpty())
        Mockito.verify(repository).summary(alice)
        Mockito.verify(repository).list(alice, null, 50, true)
        assertFailsWith<IllegalArgumentException> { service.list(alice, null, 101, false) }
        Mockito.`when`(identities.findUserById(alice)).thenReturn(null)
        assertFailsWith<ProjectAccessDeniedException> { service.summary(alice) }
        Mockito.verifyNoMoreInteractions(repository)
    }

    @Test fun retryingPublicationDoesNotGenerateAnotherStateChange() = runBlocking {
        val identities = Mockito.mock(IdentityRepository::class.java)
        Mockito.`when`(identities.findUserById(alice)).thenReturn(User(alice, displayName = "Alice", status = User.Status.ACTIVE, createdAt = now, updatedAt = now))
        val changes = mutableListOf<DeclarativeStateKey>()
        var stored: UserNotification? = null
        val repository = object : UserNotificationRepository {
            override suspend fun publish(userId: User.Id, draft: UserNotificationDraft, at: Instant): UserNotificationRepository.PublishResult {
                assertEquals(alice, userId)
                val created = stored == null
                if (created) stored = UserNotification(UserNotification.Id("notice"), userId, draft.title, draft.message, draft.level, at)
                return UserNotificationRepository.PublishResult(requireNotNull(stored), created)
            }
            override suspend fun summary(userId: User.Id) = error("unused")
            override suspend fun list(userId: User.Id, before: UserNotificationCursor?, limit: Int, unreadOnly: Boolean) = error("unused")
            override suspend fun setRead(userId: User.Id, id: UserNotification.Id, read: Boolean, at: Instant) = error("unused")
            override suspend fun markAllRead(userId: User.Id, at: Instant) = error("unused")
            override suspend fun delete(userId: User.Id, id: UserNotification.Id) = error("unused")
        }
        val service = UserNotificationApplicationService(repository, identities, DeclarativeStateChangePublisher { changes.addAll(it) })
        val draft = UserNotificationDraft("Notice", "Reusable notification", sourceKey = "producer:event")
        assertEquals(service.publish(alice, draft), service.publish(alice, draft))
        assertEquals(listOf(DeclarativeStateKey.userNotifications(alice)), changes)
    }
}
