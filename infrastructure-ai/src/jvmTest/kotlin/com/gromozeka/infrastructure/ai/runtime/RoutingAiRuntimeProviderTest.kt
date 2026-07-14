package com.gromozeka.infrastructure.ai.runtime

import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiReasoningConfig
import com.gromozeka.domain.model.ai.AiReasoningEffort
import com.gromozeka.domain.model.ai.AiRuntimeOptions
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import kotlin.test.Test
import kotlin.test.assertEquals

class RoutingAiRuntimeProviderTest {
    @Test
    fun appliesModelDefaultsWhenCallOptionsAreAbsent() {
        val defaults = AiModelConfiguration.DefaultParameters(
            maxOutputTokens = 8192,
            reasoning = AiReasoningConfig(effort = AiReasoningEffort.MAX),
        )

        val request = request().withModelDefaults(defaults)

        assertEquals(8192, request.options.maxOutputTokens)
        assertEquals(AiReasoningEffort.MAX, request.options.reasoning?.effort)
    }

    @Test
    fun preservesExplicitCallOptionsOverModelDefaults() {
        val defaults = AiModelConfiguration.DefaultParameters(
            maxOutputTokens = 8192,
            reasoning = AiReasoningConfig(effort = AiReasoningEffort.MAX),
        )
        val explicitReasoning = AiReasoningConfig(effort = AiReasoningEffort.LOW)

        val request = request(
            options = AiRuntimeOptions(
                maxOutputTokens = 1024,
                reasoning = explicitReasoning,
            )
        ).withModelDefaults(defaults)

        assertEquals(1024, request.options.maxOutputTokens)
        assertEquals(explicitReasoning, request.options.reasoning)
    }

    private fun request(options: AiRuntimeOptions = AiRuntimeOptions()) =
        AiRuntimeRequest(
            systemPrompts = emptyList(),
            messages = emptyList(),
            options = options,
        )
}
