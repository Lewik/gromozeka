package com.gromozeka.presentation.ui

import com.gromozeka.presentation.services.translation.LocalizedText
import com.gromozeka.presentation.services.translation.localizedText
import com.gromozeka.presentation.services.translation.data.Translation
import com.gromozeka.domain.model.AgentDefinition

data class AgentMentionCandidate(
    val agentDefinitionId: AgentDefinition.Id,
    val name: String,
    val mentionText: String,
    val connected: Boolean,
    val autoRespond: Boolean = false,
)

internal sealed interface AgentMentionResolution {
    data object None : AgentMentionResolution
    data class Target(val candidate: AgentMentionCandidate) : AgentMentionResolution
    data class Invalid(val message: LocalizedText) : AgentMentionResolution
}

internal fun buildAgentMentionCandidates(
    agents: List<AgentDefinition>,
    connectedAgentIds: Set<AgentDefinition.Id>,
    autoRespondAgentIds: Set<AgentDefinition.Id> = emptySet(),
): List<AgentMentionCandidate> {
    return agents.map { agent ->
        val sameNameAgents = agents.filter { it.name.equals(agent.name, ignoreCase = true) }
        val mentionText = if (sameNameAgents.size == 1) {
            "@${agent.name}"
        } else {
            "@${agent.name}#${agent.uniqueIdPrefix(sameNameAgents)}"
        }
        AgentMentionCandidate(
            agentDefinitionId = agent.id,
            name = agent.name,
            mentionText = mentionText,
            connected = agent.id in connectedAgentIds,
            autoRespond = agent.id in autoRespondAgentIds,
        )
    }.sortedWith(
        compareByDescending<AgentMentionCandidate> { it.connected }
            .thenBy { it.name.lowercase() }
            .thenBy { it.agentDefinitionId.value }
    )
}

internal fun agentResponseHint(
    message: String,
    candidates: List<AgentMentionCandidate>,
    translation: Translation,
): String =
    when (val mention = resolveAgentMention(message, candidates)) {
        is AgentMentionResolution.Invalid -> mention.message.resolve(translation)
        is AgentMentionResolution.Target -> translation.text("client.mention.replies", "agents" to mention.candidate.name)
        AgentMentionResolution.None -> {
            val names = candidates.filter { it.connected && it.autoRespond }.map { it.name }
            if (names.isEmpty()) {
                translation.text("client.mention.noAutomaticResponse")
            } else {
                translation.text("client.mention.replies", "agents" to names.joinToString())
            }
        }
    }

private fun AgentDefinition.uniqueIdPrefix(sameNameAgents: List<AgentDefinition>): String {
    val minimumLength = minOf(8, id.value.length)
    val uniqueLength = (minimumLength..id.value.length).firstOrNull { length ->
        sameNameAgents.none { other ->
            other.id != id && other.id.value.startsWith(id.value.take(length))
        }
    } ?: id.value.length
    return id.value.take(uniqueLength)
}

internal fun resolveAgentMention(
    message: String,
    candidates: List<AgentMentionCandidate>,
): AgentMentionResolution {
    val mentioned = linkedMapOf<AgentDefinition.Id, AgentMentionCandidate>()
    var searchStart = 0
    while (searchStart < message.length) {
        val markerIndex = message.indexOf('@', searchStart)
        if (markerIndex < 0) break
        searchStart = markerIndex + 1
        if (!message.isMentionStart(markerIndex)) continue

        val matches = candidates
            .filter { candidate -> message.matchesMention(markerIndex, candidate.mentionText) }
            .sortedByDescending { it.mentionText.length }
        val longestLength = matches.firstOrNull()?.mentionText?.length ?: continue
        matches.takeWhile { it.mentionText.length == longestLength }.forEach { candidate ->
            mentioned[candidate.agentDefinitionId] = candidate
        }
        searchStart = markerIndex + longestLength
    }

    return when {
        mentioned.isEmpty() -> AgentMentionResolution.None
        mentioned.size > 1 -> AgentMentionResolution.Invalid(
            localizedText("client.mention.onlyOneAgent", "mentions" to mentioned.values.joinToString { it.mentionText })
        )
        else -> {
            val candidate = mentioned.values.single()
            if (candidate.connected) {
                AgentMentionResolution.Target(candidate)
            } else {
                AgentMentionResolution.Invalid(
                    localizedText("client.mention.notConnected", "mention" to candidate.mentionText)
                )
            }
        }
    }
}

private fun String.isMentionStart(markerIndex: Int): Boolean =
    markerIndex == 0 || this[markerIndex - 1].isMentionBoundary()

private fun String.matchesMention(markerIndex: Int, mentionText: String): Boolean {
    if (!regionMatches(markerIndex, mentionText, 0, mentionText.length, ignoreCase = true)) return false
    val endIndex = markerIndex + mentionText.length
    return endIndex == length || this[endIndex].isMentionBoundary()
}

private fun Char.isMentionBoundary(): Boolean = !isLetterOrDigit() && this != '_'
