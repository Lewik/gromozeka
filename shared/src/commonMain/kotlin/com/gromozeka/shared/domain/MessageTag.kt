package com.gromozeka.shared.domain

import kotlinx.serialization.Serializable

/**
 * UI control definition for message instruction tags.
 *
 * Defines interactive controls for adding instruction metadata to messages.
 * Each control represents a selectable instruction that can be included in
 * message metadata (e.g., response routing, processing hints).
 *
 * Supports default selection for commonly-used instructions.
 *
 * This is primarily a UI/presentation concern rather than core domain logic.
 *
 * @property controls list of available instruction controls
 * @property selectedByDefault index of control selected by default (0 = first control)
 */
@Serializable
data class MessageTagDefinition(
    val controls: List<Control>,
    val selectedByDefault: Int = 0,
) {
    /**
     * Single instruction control definition.
     *
     * @property data instruction metadata to attach to message
     * @property includeInMessage true if instruction should be added to message, false to skip
     */
    @Serializable
    data class Control(
        val data: Conversation.Message.Instruction,
        val includeInMessage: Boolean = true,
    )
}
