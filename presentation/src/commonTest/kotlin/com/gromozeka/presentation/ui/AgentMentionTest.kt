package com.gromozeka.presentation.ui

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Clock

class AgentMentionTest {
    @Test
    fun `message without a known mention does not target an agent`() {
        val candidates = listOf(candidate("agent-1", "@Claude", connected = true))

        assertIs<AgentMentionResolution.None>(resolveAgentMention("mail me at user@example.com", candidates))
        assertIs<AgentMentionResolution.None>(resolveAgentMention("hello @unknown", candidates))
    }

    @Test
    fun `known mention targets connected agent case insensitively`() {
        val candidate = candidate("agent-1", "@Claude Code", connected = true)

        val resolution = assertIs<AgentMentionResolution.Target>(
            resolveAgentMention("please ask @claude code about this", listOf(candidate))
        )

        assertEquals(candidate, resolution.candidate)
    }

    @Test
    fun `disconnected mentioned agent is rejected`() {
        val resolution = assertIs<AgentMentionResolution.Invalid>(
            resolveAgentMention("@Claude help", listOf(candidate("agent-1", "@Claude", connected = false)))
        )

        assertEquals("Agent @Claude is not connected to this conversation", resolution.message)
    }

    @Test
    fun `different agents in one message are rejected`() {
        val candidates = listOf(
            candidate("agent-1", "@Claude", connected = true),
            candidate("agent-2", "@Codex", connected = true),
        )

        assertIs<AgentMentionResolution.Invalid>(
            resolveAgentMention("@Claude review this with @Codex", candidates)
        )
    }

    @Test
    fun `duplicate names receive stable unambiguous mention text`() {
        val candidates = buildAgentMentionCandidates(
            agents = listOf(agent("abcdefgh-1", "Claude"), agent("abcdefgh-2", "Claude")),
            connectedAgentIds = setOf(AgentDefinition.Id("abcdefgh-2")),
        )

        assertEquals(
            listOf("@Claude#abcdefgh-2", "@Claude#abcdefgh-1"),
            candidates.map(AgentMentionCandidate::mentionText),
        )
        assertEquals("abcdefgh-2", candidates.first().agentDefinitionId.value)
    }

    private fun candidate(
        id: String,
        mentionText: String,
        connected: Boolean,
    ): AgentMentionCandidate = AgentMentionCandidate(
        agentDefinitionId = AgentDefinition.Id(id),
        name = mentionText.removePrefix("@"),
        mentionText = mentionText,
        connected = connected,
    )

    private fun agent(id: String, name: String): AgentDefinition = AgentDefinition(
        id = AgentDefinition.Id(id),
        name = name,
        prompts = emptyList(),
        runtimeSelection = AiRuntimeSelection(AiModelConfiguration.Id("model")),
        type = AgentDefinition.Type.Global,
        createdAt = Clock.System.now(),
        updatedAt = Clock.System.now(),
    )
}
