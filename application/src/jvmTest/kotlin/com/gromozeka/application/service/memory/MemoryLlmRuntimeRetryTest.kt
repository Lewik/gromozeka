package com.gromozeka.application.service.memory

import com.gromozeka.domain.model.ai.AiRuntimeCapabilities
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiRuntimeResponse
import com.gromozeka.domain.service.AiRuntime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MemoryLlmRuntimeRetryTest {
    @Test
    fun incompleteSubscriptionResponsesAreRetried() = runBlocking {
        val runtime = EventuallySuccessfulRuntime(
            failuresBeforeSuccess = 1,
            error = IllegalStateException("OpenAI subscription stream returned an incomplete response"),
        )

        runtime.callMemoryStageWithRetry(
            request = AiRuntimeRequest(systemPrompts = emptyList(), messages = emptyList()),
            stageName = "write-router",
            logContext = "test",
            maxAttempts = 3,
            timeoutMs = 10_000,
        )

        assertEquals(2, runtime.calls)
    }

    @Test
    fun requestStreamFailuresAreNotRetriedByWrapperPrefix() = runBlocking {
        val runtime = FailingRuntime(IllegalStateException("OpenAI subscription stream failed: unsupported model"))

        assertFailsWith<IllegalStateException> {
            runtime.callMemoryStageWithRetry(
                request = AiRuntimeRequest(systemPrompts = emptyList(), messages = emptyList()),
                stageName = "write-router",
                logContext = "test",
                maxAttempts = 3,
                timeoutMs = 10_000,
            )
        }

        assertEquals(1, runtime.calls)
    }

    private class FailingRuntime(
        private val error: Throwable,
    ) : AiRuntime {
        var calls = 0
            private set

        override val capabilities: AiRuntimeCapabilities = AiRuntimeCapabilities()

        override suspend fun call(request: AiRuntimeRequest): AiRuntimeResponse {
            calls += 1
            throw error
        }

        override fun stream(request: AiRuntimeRequest): Flow<AiRuntimeResponse> = emptyFlow()
    }

    private class EventuallySuccessfulRuntime(
        private val failuresBeforeSuccess: Int,
        private val error: Throwable,
    ) : AiRuntime {
        var calls = 0
            private set

        override val capabilities: AiRuntimeCapabilities = AiRuntimeCapabilities()

        override suspend fun call(request: AiRuntimeRequest): AiRuntimeResponse {
            calls += 1
            if (calls <= failuresBeforeSuccess) throw error
            return AiRuntimeResponse(messages = emptyList())
        }

        override fun stream(request: AiRuntimeRequest): Flow<AiRuntimeResponse> = emptyFlow()
    }
}
