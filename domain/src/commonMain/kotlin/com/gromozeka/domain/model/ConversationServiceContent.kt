package com.gromozeka.domain.model

import com.gromozeka.domain.visual.VisualStatus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class PeerMessageEndpoint(
    val conversationId: Conversation.Id,
    val threadId: Conversation.Thread.Id,
    val agentId: AgentDefinition.Id,
    val conversationTitle: String,
    val agentName: String,
)

/** Literal, immutable instructions. Dynamic environment entries are resolved for each request. */
@Serializable
data class PromptSnapshot(
    val entries: List<Entry>,
    val fingerprint: String,
) {
    init {
        require(fingerprint.matches(Regex("[0-9a-f]{64}"))) { "Prompt fingerprint must be SHA-256" }
    }

    @Serializable
    sealed interface Entry {
        @Serializable
        @SerialName("catalog")
        data class Catalog(val id: Prompt.Id, val name: String, val text: String) : Entry

        @Serializable
        @SerialName("environment")
        data object Environment : Entry
    }
}

@Serializable
data class VisualObservation(
    val id: String,
    val title: String,
    val status: VisualStatus,
    val revision: Long,
    val state: JsonObject,
) {
    init {
        require(id.isNotBlank())
        require(revision > 0)
    }
}

fun Conversation.Message.promptSnapshotOrNull(): Conversation.Message.ContentItem.PromptMessage? {
    val snapshots = content.filterIsInstance<Conversation.Message.ContentItem.PromptMessage>()
    check(snapshots.size <= 1) { "A message cannot contain several prompt snapshots" }
    if (snapshots.isEmpty()) {
        check(role != Conversation.Message.Role.PROMPT) { "Prompt message has no snapshot" }
        return null
    }
    check(role == Conversation.Message.Role.PROMPT && author is Conversation.Message.Author.Runtime) {
        "Prompt snapshots must be Runtime-authored prompt messages"
    }
    return snapshots.single()
}

/** Provider USER roles are not evidence that an input came from the human. */
fun Conversation.Message.isHumanInput(): Boolean =
    role == Conversation.Message.Role.USER &&
        author is Conversation.Message.Author.User &&
        content.any { it is Conversation.Message.ContentItem.UserMessage || it is Conversation.Message.ContentItem.VisualInteraction ||
            it is Conversation.Message.ContentItem.ImageItem || it is Conversation.Message.ContentItem.DocumentItem ||
            it is Conversation.Message.ContentItem.ArtifactItem }

fun requireEditableHistoryContent(content: List<Conversation.Message.ContentItem>) {
    require(content.none { it is Conversation.Message.ContentItem.PeerMessage || it is Conversation.Message.ContentItem.RuntimeNotification ||
        it is Conversation.Message.ContentItem.PromptMessage || it is Conversation.Message.ContentItem.VisualUpdated }) {
        "History edits cannot introduce service message blocks"
    }
}
