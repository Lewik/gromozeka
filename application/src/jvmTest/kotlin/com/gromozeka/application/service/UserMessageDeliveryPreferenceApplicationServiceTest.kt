package com.gromozeka.application.service

import com.gromozeka.domain.model.*
import com.gromozeka.domain.repository.UserMessageDeliveryPreferenceRepository
import com.gromozeka.domain.service.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class UserMessageDeliveryPreferenceApplicationServiceTest {
    @Test fun `default is steer and durable preference and invalidation are scoped to user`() = runBlocking {
        val values = mutableMapOf<User.Id, MessageDelivery>()
        val repository = object : UserMessageDeliveryPreferenceRepository {
            override suspend fun find(userId: User.Id) = values[userId]
            override suspend fun save(userId: User.Id, mode: MessageDelivery) { values[userId] = mode }
        }
        val changes = mutableListOf<DeclarativeStateKey>()
        val invalidator = DeclarativeStateInvalidator { changes += it }
        val service = UserMessageDeliveryPreferenceApplicationService(repository, invalidator)
        val alice = User.Id("alice"); val bob = User.Id("bob")
        assertEquals(MessageDelivery.STEER, service.get(alice))
        assertEquals(MessageDelivery.QUEUE, service.set(alice, MessageDelivery.QUEUE))
        assertEquals(MessageDelivery.STEER, service.get(bob))
        assertEquals(listOf(DeclarativeStateKey.messageDeliveryPreference(alice)), changes)
        assertEquals(MessageDelivery.QUEUE,
            UserMessageDeliveryPreferenceApplicationService(repository, invalidator).get(alice))
        service.set(alice, MessageDelivery.STEER)
        assertEquals(MessageDelivery.STEER, service.get(alice))
    }
}
