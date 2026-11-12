package com.gromozeka.server

import com.gromozeka.application.service.UserNotificationApplicationService
import com.gromozeka.domain.model.*
import com.gromozeka.domain.repository.IdentityRepository
import com.gromozeka.domain.repository.UserNotificationRepository
import com.gromozeka.domain.service.DeclarativeStateChangePublisher
import com.gromozeka.domain.service.DeclarativeStateKey
import com.gromozeka.domain.service.UserDirectoryService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.mockito.Mockito
import kotlin.test.*
import kotlin.time.Instant

class UserNotificationToolsTest {
    private val now = Instant.parse("2026-11-12T00:00:00Z")
    private val actor = User(User.Id("notification-user"), displayName = "User", status = User.Status.ACTIVE,
        role = User.Role.entries.first { it != User.Role.OWNER }, createdAt = now, updatedAt = now)
    private val context = ServerToolContext(actor)

    @Test fun `authenticated inbox supports pagination without marking anything read`() = runBlocking {
        val fixture = fixture()
        val list = fixture.tools.single { it.definition.name == "grz_user_notifications_list" }
        val first = list.invokeStructured(context, buildJsonObject { put("limit", 1) })
        assertEquals(JsonPrimitive(true), first["success"])
        val page = first.getValue("result").jsonObject
        assertEquals("own-2", page.getValue("items").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content)
        val second = list.invokeStructured(context, buildJsonObject { put("before", page.getValue("nextCursor")) })
        assertContains(second.toString(), "own-1")
        assertFalse(second.toString().contains("OTHER_INBOX"))
        assertEquals(0, fixture.repository.readChanges)
        assertTrue(fixture.repository.items.values.all { it.readAt == null })
        assertTrue(fixture.changes.isEmpty())
    }

    @Test fun `mark read is owner scoped and idempotent for a non administrator`() = runBlocking {
        val fixture = fixture()
        val mark = fixture.tools.single { it.definition.name == "grz_user_notification_mark_read" }
        val list = fixture.tools.single { it.definition.name == "grz_user_notifications_list" }
        val args = buildJsonObject { put("notification_id", "own-1") }
        val first = mark.invokeStructured(context, args)
        val again = mark.invokeStructured(context, args)
        assertEquals(JsonPrimitive(true), first["success"])
        assertEquals(first.getValue("result"), again.getValue("result"))
        assertNotNull(fixture.repository.items.getValue("own-1").readAt)
        assertTrue(mark.invoke(context, buildJsonObject { put("notification_id", "other") }).isError == true)
        assertNull(fixture.repository.items.getValue("other").readAt)
        val unread = list.invokeStructured(context, buildJsonObject { put("unread_only", true) })
            .getValue("result").jsonObject.getValue("items").jsonArray
        assertEquals(listOf("own-2"), unread.map { it.jsonObject.getValue("id").jsonPrimitive.content })
        assertTrue(fixture.changes.all { it == DeclarativeStateKey.userNotifications(actor.id) })
    }

    @Test fun `no caller chosen recipient or implicit mutation is accepted`() = runBlocking {
        val fixture = fixture()
        val list = fixture.tools.single { it.definition.name == "grz_user_notifications_list" }
        val mark = fixture.tools.single { it.definition.name == "grz_user_notification_mark_read" }
        for (args in listOf(
            buildJsonObject { put("user_id", "other-user") },
            buildJsonObject { put("limit", 0) },
            buildJsonObject { put("limit", 101) },
            buildJsonObject { put("unread_only", "true") },
        )) assertTrue(list.invoke(context, args).isError == true, args.toString())
        assertTrue(mark.invoke(context, buildJsonObject { put("notification_id", "own-1"); put("user_id", actor.id.value) }).isError == true)
        assertTrue(mark.callResult("{\"notification_id\":\"own-1\"}", null).isError)
        assertTrue(fixture.repository.items.values.all { it.readAt == null })
        assertEquals(CONTROL_TOOL_SOURCE, list.definition.source)
        assertEquals(true, list.definition.annotations?.readOnlyHint)
        assertEquals(false, mark.definition.annotations?.readOnlyHint)
        assertEquals(true, mark.definition.annotations?.idempotentHint)
    }

    private suspend fun fixture(): Fixture {
        val repository = Inbox()
        val identities = Mockito.mock(IdentityRepository::class.java)
        Mockito.`when`(identities.findUserById(actor.id)).thenReturn(actor)
        val changes = mutableListOf<DeclarativeStateKey>()
        val service = UserNotificationApplicationService(repository, identities,
            DeclarativeStateChangePublisher { changes.addAll(it) })
        val directory = object : UserDirectoryService {
            override suspend fun findActiveById(id: User.Id): User? = actor.takeIf { it.id == id }
            override suspend fun listActive(): List<User> = listOf(actor)
        }
        return Fixture(repository, changes, UserNotificationToolContributor(testServerToolFactory(directory), service).callbacks)
    }

    private data class Fixture(val repository: Inbox, val changes: List<DeclarativeStateKey>,
        val tools: List<com.gromozeka.domain.tool.AiToolCallback>)

    private inner class Inbox : UserNotificationRepository {
        val items = linkedMapOf(
            "own-1" to notification("own-1", actor.id),
            "own-2" to notification("own-2", actor.id),
            "other" to notification("other", User.Id("other-user")).copy(message = "OTHER_INBOX"),
        )
        var readChanges = 0
        private fun notification(id: String, user: User.Id) = UserNotification(UserNotification.Id(id), user,
            "Notification", "Content $id", UserNotification.Level.INFO, now)
        override suspend fun publish(userId: User.Id, draft: UserNotificationDraft, at: Instant): UserNotificationRepository.PublishResult = error("unused")
        override suspend fun summary(userId: User.Id): UserNotificationSummary = error("unused")
        override suspend fun list(userId: User.Id, before: UserNotificationCursor?, limit: Int, unreadOnly: Boolean): UserNotificationPage {
            val selected = items.values.filter { it.userId == userId && (!unreadOnly || it.readAt == null) &&
                (before == null || it.createdAt < before.createdAt || (it.createdAt == before.createdAt && it.id.value < before.id.value)) }
                .sortedByDescending { it.id.value }
            val page = selected.take(limit)
            return UserNotificationPage(page, if (selected.size > limit) page.last().let { UserNotificationCursor(it.createdAt, it.id) } else null)
        }
        override suspend fun setRead(userId: User.Id, id: UserNotification.Id, read: Boolean, at: Instant): UserNotification {
            val old = items[id.value]?.takeIf { it.userId == userId } ?: throw UserNotificationNotFoundException()
            assertTrue(read)
            readChanges++
            return old.copy(readAt = old.readAt ?: at).also { items[id.value] = it }
        }
        override suspend fun markAllRead(userId: User.Id, at: Instant): Int = error("unused")
        override suspend fun delete(userId: User.Id, id: UserNotification.Id): Boolean = error("unused")
    }
}
