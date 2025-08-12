package com.gromozeka.shared.domain.message

import kotlinx.serialization.Serializable

@Serializable
data class MessageTag(
    val title: String,       // Display name for the button: "Ultrathink", "Readonly", etc.
    val instruction: String  // Text sent to Claude in <instructions> section and used for tooltips
)