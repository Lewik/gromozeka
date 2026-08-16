package com.gromozeka.application.service

import com.gromozeka.domain.service.DeclarativeStateInvalidator
import com.gromozeka.domain.service.DeclarativeStateKey
import com.gromozeka.domain.service.DeclarativeStateResource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals

class DeclarativeStateSyncApplicationServiceTest {
    @Test
    fun `invalidations are conflated per resource key`() = runBlocking {
        val service = DeclarativeStateSyncApplicationService(this)
        val conversations = DeclarativeStateKey(
            DeclarativeStateResource.PROJECT_CONVERSATIONS,
            "project-1",
        )
        val otherProject = conversations.copy(scopeId = "project-2")
        val subscription = service.subscribe(conversations)

        assertEquals(0, subscription.invalidations.first().cursor.generation)
        service.invalidate(conversations)
        service.invalidate(otherProject)
        service.invalidate(conversations)

        assertEquals(2, subscription.invalidations.first().cursor.generation)
        assertEquals(0, service.snapshot(otherProject).cursor.generation)
        subscription.close()
    }

    @Test
    fun `committed change listener invalidates every distinct key`() = runBlocking {
        val invalidated = mutableListOf<DeclarativeStateKey>()
        val listener = DeclarativeStateChangedEventListener(
            invalidator = DeclarativeStateInvalidator(invalidated::add),
            scope = this,
        )
        val keys = setOf(DeclarativeStateKey.projects, DeclarativeStateKey.workers)

        listener.onChanged(DeclarativeStateChangedEvent(keys))
        yield()

        assertEquals(keys, invalidated.toSet())
    }
}
