package com.gromozeka.infrastructure.ai.claude

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClaudeCodeDiagnosticsTest {
    @Test
    fun countsUnicodeEscapesAcrossEveryPossibleDeltaBoundary() {
        val rawJson = """{"text":"\u041c\u043eре PRIVATE"}"""
        for (boundary in 0..rawJson.length) {
            val diagnostics = ClaudeCodeJsonDeltaDiagnostics()
            diagnostics.accept(jsonDelta(rawJson.take(boundary)))
            diagnostics.accept(jsonDelta(rawJson.drop(boundary)))
            assertEquals(2L, diagnostics.unicodeEscapes)
            assertEquals(rawJson.length.toLong(), diagnostics.jsonChars)
            assertFalse(diagnostics.summary().contains("PRIVATE"))
        }
    }

    @Test
    fun distinguishesLiteralBackslashSequencesFromUnicodeEscapes() {
        val diagnostics = ClaudeCodeJsonDeltaDiagnostics()
        val rawJson = """{"text":"\\u041c \u043e море"}"""
        rawJson.forEach { diagnostics.accept(jsonDelta(it.toString())) }
        assertEquals(1L, diagnostics.unicodeEscapes)
        assertEquals(rawJson.length.toLong(), diagnostics.jsonChars)
    }

    @Test
    fun doesNotCountTextDeltasAsJsonGeneration() {
        val diagnostics = ClaudeCodeJsonDeltaDiagnostics()
        diagnostics.accept(Json.parseToJsonElement("""{"type":"stream_event","event":{"delta":{"type":"text_delta","text":"\\u041c"}}}""").jsonObject)
        assertEquals(0L, diagnostics.jsonChars)
        assertEquals(0L, diagnostics.unicodeEscapes)
        assertEquals(1L, diagnostics.textUnicodeEscapes)
    }

    @Test
    fun countsJsonTextEscapesAcrossDeltaBoundariesWithoutLoggingText() {
        val text = """{"answer":"Привет \u0410 \\u0411 PRIVATE"}"""
        for (boundary in 0..text.length) {
            val diagnostics = ClaudeCodeJsonDeltaDiagnostics()
            for (fragment in listOf(text.take(boundary), text.drop(boundary))) {
                diagnostics.accept(buildJsonObject {
                    put("type", "stream_event")
                    putJsonObject("event") {
                        putJsonObject("delta") {
                            put("type", "text_delta")
                            put("text", fragment)
                        }
                    }
                })
            }
            assertEquals(1L, diagnostics.textUnicodeEscapes)
            assertEquals(text.length.toLong(), diagnostics.textChars)
            assertEquals(0L, diagnostics.unicodeEscapes)
            assertFalse(diagnostics.summary().contains("PRIVATE"))
        }
    }

    @Test
    fun recordsStructuredOutputShapeWithoutContents() {
        val summary = Json.parseToJsonElement("""{"type":"assistant","message":{"id":"msg-1","model":"test","content":[{"type":"tool_use","name":"StructuredOutput","input":{"response":{"response":{"kind":"final_answer","final_answer":"PRIVATE_CONTENT"}}}}]}}""").jsonObject.diagnosticEventSummary()
        assertTrue(summary.contains("responseKeys=[response]"))
        assertFalse(summary.contains("PRIVATE_CONTENT"))
    }

    @Test
    fun recordsValidationFailureWithoutContents() {
        val summary = Json.parseToJsonElement("""{"type":"user","message":{"content":[{"type":"tool_result","is_error":true,"content":"PRIVATE_ERROR"}]}}""").jsonObject.diagnosticEventSummary()
        assertTrue(summary.contains("toolErrors=1"))
        assertFalse(summary.contains("PRIVATE_ERROR"))
    }

    @Test
    fun recordsPartialTimingMetadataWithoutText() {
        val summary = Json.parseToJsonElement("""{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"PRIVATE"}}}""").jsonObject.diagnosticEventSummary()
        assertTrue(summary.contains("deltaChars=7"))
        assertFalse(summary.contains("PRIVATE"))
    }

    private fun jsonDelta(fragment: String) = buildJsonObject {
        put("type", "stream_event")
        putJsonObject("event") {
            put("type", "content_block_delta")
            putJsonObject("delta") {
                put("type", "input_json_delta")
                put("partial_json", fragment)
            }
        }
    }
}
