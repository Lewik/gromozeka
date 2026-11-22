package com.gromozeka.infrastructure.ai.openai

import com.openai.models.responses.EasyInputMessage
import com.openai.models.responses.ResponseInputItem
import kotlin.test.*

class OpenAiResponsesContinuationsTest {
    private fun item(text: String) = ResponseInputItem.ofEasyInputMessage(EasyInputMessage.builder()
        .role(EasyInputMessage.Role.USER).content(text).build())
    private fun scope(agent: String = "agent") = OpenAiResponsesContinuations.Scope(
        "actor", "project", "conversation", "thread", agent, "connection", "configuration", "model", "endpoint", "auth-hash", "settings-hash")

    @Test fun `stale completions and failures cannot replace or delete a newer cursor`() {
        val state = OpenAiResponsesContinuations()
        val key = scope()
        val input = listOf(item("first"))
        val first = state.plan(key, input, true)
        val concurrent = state.plan(key, input, true)
        state.record(first, "new-parent", listOf(item("answer")))
        state.record(concurrent, "stale-parent", listOf(item("stale-answer")))
        state.forget(concurrent)
        val next = state.plan(key, input + item("answer") + item("tail"), true)
        assertEquals("new-parent", next.previousResponseId)
        assertEquals(1, next.input.size)
        assertTrue(next.observed!!.fingerprint.matches(Regex("[0-9a-f]{64}")))
    }

    @Test fun `content mismatch never reuses parent even with equal item count`() {
        val state = OpenAiResponsesContinuations()
        val initial = state.plan(scope(), listOf(item("before")), true)
        state.record(initial, "parent", emptyList())
        val changed = state.plan(scope(), listOf(item("after")), true)
        assertNull(changed.previousResponseId)
        assertEquals(OpenAiResponsesContinuations.Reason.PREFIX_CHANGED, changed.reason)
        val shortened = state.plan(scope(), emptyList(), true)
        assertNull(shortened.previousResponseId)
    }

    @Test fun `continuation can contain no new input items without duplicating the old prefix`() {
        val state = OpenAiResponsesContinuations()
        val input = listOf(item("prefix"))
        state.record(state.plan(scope(), input, true), "parent", emptyList())
        val plan = state.plan(scope(), input, true)
        assertEquals("parent", plan.previousResponseId)
        assertTrue(plan.input.isEmpty())
    }

    @Test fun `the cache is bounded and eviction safely returns to full input`() {
        val state = OpenAiResponsesContinuations()
        val input = listOf(item("prefix"))
        repeat(129) { index ->
            val key = scope("agent-$index")
            state.record(state.plan(key, input, true), "parent-$index", emptyList())
        }
        assertNull(state.plan(scope("agent-0"), input, true).previousResponseId)
        assertEquals("parent-128", state.plan(scope("agent-128"), input, true).previousResponseId)
    }
}
