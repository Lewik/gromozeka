package com.gromozeka.application.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class AgentSessionQueryTest {
    @Test fun `literal title search preserves regex punctuation and Unicode case folding`() {
        assertTrue(AgentSessionQuery(query = "c++ [main]").matchesTitle("Project C++ [MAIN]"))
        assertFalse(AgentSessionQuery(query = "c++ [main]").matchesTitle("Project C main"))
        assertTrue(AgentSessionQuery(query = "архитектор").matchesTitle("АРХИТЕКТОР (slot)"))
    }

    @Test fun `regex searches within titles with explicit anchors and no backtracking engine`() {
        assertTrue(AgentSessionQuery(query = "архитектор|разраб", queryMode = AgentSessionQuery.Mode.REGEX).matchesTitle("Работа: АРХИТЕКТОР"))
        assertFalse(AgentSessionQuery(query = "^архитектор$", queryMode = AgentSessionQuery.Mode.REGEX).matchesTitle("Архитектор (slot)"))
        assertFalse(AgentSessionQuery(query = "(a+)+$", queryMode = AgentSessionQuery.Mode.REGEX).matchesTitle("a".repeat(100_000) + "!"))
        for (pattern in listOf("[", "(?<=x)y", "(a)\\1")) {
            assertFailsWith<IllegalArgumentException> { AgentSessionQuery(query = pattern, queryMode = AgentSessionQuery.Mode.REGEX) }
        }
    }

    @Test fun `query validation does not invent missing bounds or accept inconsistent ranges`() {
        val now = Instant.parse("2026-11-06T00:00:00Z")
        assertTrue(AgentSessionQuery().matchesTime(null))
        assertFalse(AgentSessionQuery(messageGt = now).matchesTime(null))
        assertFalse(AgentSessionQuery(messageLt = now).matchesTime(null))
        assertFalse(AgentSessionQuery(messageGt = now).matchesTime(now))
        assertFalse(AgentSessionQuery(messageLt = now).matchesTime(now))
        assertFailsWith<IllegalArgumentException> { AgentSessionQuery(messageGt = now, messageLt = now) }
        assertFailsWith<IllegalArgumentException> { AgentSessionQuery(limit = 0) }
        assertFailsWith<IllegalArgumentException> { AgentSessionQuery(limit = 201) }
        assertFailsWith<IllegalArgumentException> { AgentSessionQuery(query = "x".repeat(513)) }
        assertFailsWith<IllegalArgumentException> { AgentSessionQuery(cursor = "x".repeat(4097)) }
    }

    @Test fun `cursor round trip retains null timestamp and is bound to caller query scope`() {
        val position = AgentSessionPosition(null, "conversation", "agent")
        val cursor = position.cursor("caller-query-scope")
        assertEquals(position, AgentSessionQuery(cursor = cursor).after("caller-query-scope"))
        assertFailsWith<IllegalArgumentException> { AgentSessionQuery(cursor = cursor).after("other-caller") }
        assertFailsWith<IllegalArgumentException> { AgentSessionQuery(cursor = "%%%invalid").after("caller-query-scope") }
    }
}
