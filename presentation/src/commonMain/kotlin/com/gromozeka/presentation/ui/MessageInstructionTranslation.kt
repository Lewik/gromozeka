package com.gromozeka.presentation.ui

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.MessageInstructionGroup
import com.gromozeka.domain.model.Conversation.TurnTerminationReason
import com.gromozeka.presentation.services.translation.data.Translation

internal val liveSteeringInstruction = Conversation.Message.Instruction.UserInstruction(
    id = "mid_turn_steer",
    title = "Live steering update",
    description = "This user message was submitted while the assistant was already working. " +
        "Treat it as additional steering for the active turn and incorporate it at the next safe boundary, " +
        "usually after the current tool result. Do not restart or discard completed work unless the user explicitly asks."
)

private val defaultInstructionGroups = MessageInstructionGroup.defaults()
private val defaultInstructionControls = defaultInstructionGroups.flatMap { it.controls }

internal fun MessageInstructionGroup.displayTitle(translation: Translation): String =
    if (defaultInstructionGroups.any { it.id == id && it.title == title }) {
        translation.text("client.instructions.writeAccess")
    } else title

internal fun MessageInstructionGroup.Control.displayShortLabel(translation: Translation): String {
    val default = defaultInstructionControls.firstOrNull { it.data == data && it.shortLabel == shortLabel }
        ?: return shortLabel
    return translation.text(when (default.data.id) {
        "mode_readonly" -> "client.instructions.readOnlyShort"
        "mode_writable" -> "client.instructions.writableShort"
        else -> return shortLabel
    })
}

internal fun Conversation.Message.Instruction.displayTitle(translation: Translation): String {
    val resourceKey = when (this) {
        is Conversation.Message.Instruction.MessageInputRuntimeContext -> "client.instructions.inputContext"
        is Conversation.Message.Instruction.MessageTemporalRuntimeContext -> "client.instructions.messageTime"
        is Conversation.Message.Instruction.RevealedSecretRuntimeContext -> "client.instructions.revealedSecrets"
        is Conversation.Message.Instruction.UserSituationRuntimeContext -> "client.instructions.userSituation"
        is Conversation.Message.Instruction.PreviousTurnTerminated -> when (reason) {
            TurnTerminationReason.STOPPED -> "client.instructions.previousStopped"
            TurnTerminationReason.INTERRUPTED -> "client.instructions.previousInterrupted"
        }
        is Conversation.Message.Instruction.WorkspaceContext -> "client.instructions.workspaceContext"
        is Conversation.Message.Instruction.ResponseExpected -> "client.instructions.responseExpected"
        is Conversation.Message.Instruction.Source.User -> "search.role.user"
        is Conversation.Message.Instruction.Source.Agent -> "settingsUi.agent"
        is Conversation.Message.Instruction.UserInstruction -> null
    }
    if (resourceKey != null) return translation.text(resourceKey)
    if (this == liveSteeringInstruction) return translation.text("client.instructions.liveSteering")
    val default = defaultInstructionControls.firstOrNull { it.data == this } ?: return title
    return translation.text(when (default.data.id) {
        "mode_readonly" -> "client.instructions.readOnly"
        "mode_writable" -> "client.instructions.writable"
        else -> return title
    })
}

internal fun Conversation.Message.Instruction.displayDescription(translation: Translation): String {
    val default = defaultInstructionControls.firstOrNull { it.data == this } ?: return description
    return translation.text(when (default.data.id) {
        "mode_readonly" -> "client.instructions.readOnlyDescription"
        "mode_writable" -> "client.instructions.writableDescription"
        else -> return description
    })
}
