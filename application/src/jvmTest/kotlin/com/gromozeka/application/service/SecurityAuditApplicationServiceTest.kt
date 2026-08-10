package com.gromozeka.application.service

import com.gromozeka.domain.model.SecurityAuditEvent
import com.gromozeka.domain.model.SecurityAuditRecord
import com.gromozeka.domain.model.User
import com.gromozeka.domain.repository.SecurityAuditRepository
import com.gromozeka.domain.service.UserAdministrationDeniedException
import kotlinx.coroutines.runBlocking
import kotlin.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SecurityAuditApplicationServiceTest {
    private val repository = FakeSecurityAuditRepository()
    private val service = SecurityAuditApplicationService(repository)
    private val owner = auditUser(User.Role.OWNER)

    @Test
    fun `record appends immutable event with sorted attributes`() = runBlocking {
        service.record(
            SecurityAuditRecord(
                actorUserId = owner.id,
                action = SecurityAuditEvent.Action.USER_UPDATED,
                targetType = SecurityAuditEvent.TargetType.USER,
                targetId = "target",
                attributes = linkedMapOf("z" to "last", "a" to "first"),
            )
        )
        val recorded = repository.events.single()

        assertEquals(listOf("a", "z"), recorded.attributes.keys.toList())
        assertEquals(listOf(recorded), repository.events)
    }

    @Test
    fun `only active runtime owner can read recent audit`() = runBlocking {
        service.record(
            SecurityAuditRecord(
                actorUserId = owner.id,
                action = SecurityAuditEvent.Action.RUNTIME_BOOTSTRAPPED,
                targetType = SecurityAuditEvent.TargetType.RUNTIME,
                targetId = "runtime",
            )
        )

        assertEquals(1, service.listRecent(owner, 100).size)
        assertFailsWith<UserAdministrationDeniedException> {
            service.listRecent(auditUser(User.Role.MEMBER), 100)
        }
        Unit
    }
}

private class FakeSecurityAuditRepository : SecurityAuditRepository {
    val events = mutableListOf<SecurityAuditEvent>()

    override suspend fun append(event: SecurityAuditEvent): SecurityAuditEvent =
        event.also(events::add)

    override suspend fun listRecent(limit: Int): List<SecurityAuditEvent> =
        events.asReversed().take(limit)
}

private fun auditUser(role: User.Role): User {
    val now = Clock.System.now()
    return User(
        id = User.Id("audit-${role.name.lowercase()}"),
        username = "audit-${role.name.lowercase()}",
        displayName = "Audit ${role.name}",
        status = User.Status.ACTIVE,
        role = role,
        createdAt = now,
        updatedAt = now,
    )
}
