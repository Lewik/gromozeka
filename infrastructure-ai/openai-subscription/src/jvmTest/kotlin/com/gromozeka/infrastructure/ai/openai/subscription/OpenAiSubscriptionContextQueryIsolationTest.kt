package com.gromozeka.infrastructure.ai.openai.subscription

import com.gromozeka.domain.model.ai.*
import kotlin.test.*

class OpenAiSubscriptionContextQueryIsolationTest {
    @Test fun `query transport never reuses main or another query cache while prompt namespace stays separate`() {
        val main = request("target", "CONVERSATION")
        val first = request("context-query:one", AI_CONTEXT_QUERY_PURPOSE)
        val second = request("context-query:two", AI_CONTEXT_QUERY_PURPOSE)
        assertEquals("target", main.subscriptionTransportNamespace("runtime"))
        assertEquals("context-query:one:context-query:runtime", first.subscriptionTransportNamespace("runtime"))
        assertNotEquals(main.subscriptionTransportNamespace("runtime"), first.subscriptionTransportNamespace("runtime"))
        assertNotEquals(first.subscriptionTransportNamespace("runtime"), second.subscriptionTransportNamespace("runtime"))
        assertEquals("target", first.options.toolContext["promptCacheKey"])
        assertEquals("target", second.options.toolContext["promptCacheKey"])
    }

    @Test fun `existing auxiliary and ordinary transport namespaces stay unchanged`() {
        assertEquals("target", request("target", null).subscriptionTransportNamespace("runtime"))
        for (purpose in listOf("MESSAGE_SQUASH", "AGENT_RESPONSE_REVIEW")) {
            assertEquals("target:message-squash:runtime", request("target", purpose).subscriptionTransportNamespace("runtime"))
        }
    }

    private fun request(id: String, purpose: String?) = AiRuntimeRequest(emptyList(), emptyList(), options = AiRuntimeOptions(
        usagePurpose = purpose, toolContext = mapOf("conversationId" to id, "promptCacheKey" to "target"),
    ))
}
