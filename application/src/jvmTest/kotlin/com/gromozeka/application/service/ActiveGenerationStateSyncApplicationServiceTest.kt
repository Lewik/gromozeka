package com.gromozeka.application.service

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.service.ActiveGenerationSnapshot
import com.gromozeka.domain.service.ConversationRuntimeTask
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class ActiveGenerationStateSyncApplicationServiceTest {
    @Test
    fun `frequent updates conflate to the latest snapshot`() = runBlocking {
        val service = ActiveGenerationStateSyncApplicationService(this)
        val conversationId = Conversation.Id("conversation-1")
        val subscription = service.subscribe(conversationId)
        assertEquals(0, subscription.invalidations.first().cursor.generation)

        service.publish(snapshot(conversationId, "generation-1"))
        service.publish(
            snapshot(conversationId, "generation-1").copy(
                updatedAt = Instant.parse("2026-08-17T12:00:02Z"),
            )
        )
        service.publish(snapshot(conversationId, "generation-2"))

        assertEquals(3, subscription.invalidations.first().cursor.generation)
        assertEquals("generation-2", subscription.snapshot().value?.generationId)
        subscription.close()
    }

    @Test
    fun `late cleanup cannot clear a newer generation`() = runBlocking {
        val service = ActiveGenerationStateSyncApplicationService(this)
        val conversationId = Conversation.Id("conversation-1")

        service.publish(snapshot(conversationId, "generation-1"))
        service.publish(snapshot(conversationId, "generation-2"))
        service.clear(conversationId, "generation-1")

        assertEquals("generation-2", service.snapshot(conversationId).value?.generationId)
        service.clear(conversationId, "generation-2")
        assertNull(service.snapshot(conversationId).value)
    }

    @Test
    fun `late update cannot replace a newer generation`() = runBlocking {
        val service = ActiveGenerationStateSyncApplicationService(this)
        val conversationId = Conversation.Id("conversation-1")
        val older = snapshot(conversationId, "generation-1")
        val newer = snapshot(conversationId, "generation-2").copy(
            startedAt = Instant.parse("2026-08-17T12:01:00Z"),
            updatedAt = Instant.parse("2026-08-17T12:01:00Z"),
        )

        service.publish(newer)
        service.publish(older)

        assertEquals("generation-2", service.snapshot(conversationId).value?.generationId)
    }

    private fun snapshot(
        conversationId: Conversation.Id,
        generationId: String,
        phase: ActiveGenerationSnapshot.Phase = ActiveGenerationSnapshot.Phase.WAITING_FOR_MODEL,
    ): ActiveGenerationSnapshot = ActiveGenerationSnapshot(
        generationId = generationId,
        conversationId = conversationId,
        taskId = ConversationRuntimeTask.Id("task-1"),
        provider = "OPENAI_SUBSCRIPTION",
        modelName = "gpt-5.6-sol",
        iteration = 1,
        phase = phase,
        startedAt = Instant.parse("2026-08-17T12:00:00Z"),
        updatedAt = Instant.parse("2026-08-17T12:00:01Z"),
        inputMessageCount = 4,
        inputContentItemCount = 5,
        systemPromptCount = 2,
        availableToolCount = 8,
    )
}
