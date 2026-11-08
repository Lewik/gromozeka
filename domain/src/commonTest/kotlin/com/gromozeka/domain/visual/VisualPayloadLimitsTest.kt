package com.gromozeka.domain.visual

import kotlinx.serialization.json.*
import kotlin.test.*

class VisualPayloadLimitsTest {
    @Test fun fifteenBusyAgentsWithQueuesAndLongUrlsFitOneCompleteUpdate() {
        val agents = JsonArray((1..15).map { agent -> buildJsonObject {
            put("id", "agent-$agent")
            put("name", "Агент $agent — проверка состояния")
            put("busy", true)
            put("task", "Проверка состояния агента и очереди задач — ".repeat(4))
            put("url", "https://example.invalid/agents/$agent/" + "segment/".repeat(75))
            put("queue", JsonArray((1..6).map { queued -> buildJsonObject {
                put("id", "$agent-$queued")
                put("title", "Следующая задача с подробным названием — ".repeat(4))
                put("url", "https://example.invalid/agents/$agent/jobs/$queued/" + "segment/".repeat(75))
            } }))
        } })
        val state = buildJsonObject { put("form", JsonObject(emptyMap())); put("data", buildJsonObject { put("agents", agents) }) }
        val bytes = state.toString().encodeToByteArray()
        assertTrue(bytes.size > 65_536, "Exercise both former output and action-input limits")
        assertTrue(bytes.size <= VisualLimits.STATE_BYTES)
        validateVisualStateShape(state)
        val record = VisualOutputFramer().append(bytes + byteArrayOf(10)).single()
        assertNull(record.error)
        assertEquals(state, Json.parseToJsonElement(record.json!!))
        val document = VisualDocumentCompiler.compile("""<html><head><title>Agents</title><state-schema><![CDATA[{"type":"object","properties":{"form":{"type":"object"},"data":{"type":"object"}},"required":["form","data"]}]]></state-schema></head><body><for-each items="{data.agents}" as="agent" key="{agent.id}"><div><h3>{agent.name}</h3><p>{agent.task}</p><a href="{agent.url}">Details</a><for-each items="{agent.queue}" as="job" key="{job.id}"><p>{job.title}</p><a href="{job.url}">Open</a></for-each></div></for-each></body></html>""")
        VisualDocumentCompiler.validateState(document, state)
        assertEquals(15, VisualDocumentCompiler.render(document, state).size)
    }

    @Test fun exactStateAndOutputLimitsRemainEnforced() {
        fun state(text: String) = buildJsonObject { put("form", JsonObject(emptyMap())); put("data", buildJsonObject { put("text", text) }) }
        val overhead = state("").toString().encodeToByteArray().size
        val exact = state("x".repeat(VisualLimits.STATE_BYTES - overhead))
        assertEquals(VisualLimits.STATE_BYTES, exact.toString().encodeToByteArray().size)
        validateVisualStateShape(exact)
        assertNull(VisualOutputFramer().append((exact.toString() + "\r\n").encodeToByteArray()).single().error)
        assertFailsWith<IllegalArgumentException> { validateVisualStateShape(state("x".repeat(VisualLimits.STATE_BYTES - overhead + 1))) }
        assertNotNull(VisualOutputFramer().append((exact.toString() + " \n").encodeToByteArray()).single().error)
    }
}
