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

    @Test fun `explicit input calls use invocation identity even with reused runtime and parent provenance`() {
        val base = request("parent", AI_ISOLATED_CALL_PURPOSE)
        fun isolated(id: String) = base.copy(options = base.options.copy(toolContext = base.options.toolContext + ("aiCallDiagnosticId" to id)))
        assertEquals("parent:isolated-call:one", isolated("one").subscriptionTransportNamespace("runtime"))
        assertNotEquals(isolated("one").subscriptionTransportNamespace("runtime"), isolated("two").subscriptionTransportNamespace("runtime"))
        assertFailsWith<IllegalArgumentException> { base.subscriptionTransportNamespace("runtime") }
    }

    private fun request(id: String, purpose: String?) = AiRuntimeRequest(emptyList(), emptyList(), options = AiRuntimeOptions(
        usagePurpose = purpose, toolContext = mapOf("conversationId" to id, "promptCacheKey" to "target"),
    ))
}
