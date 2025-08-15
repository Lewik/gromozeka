package com.gromozeka.shared.domain.message

import kotlinx.serialization.Serializable

@Serializable
data class MessageTagDefinition(
    val controls: List<Control>,
    val selectedByDefault: Int = 0
) {
    @Serializable
    data class Control(
        val data: Data,
        val includeInMessage: Boolean = true
    )
    
    @Serializable
    data class Data(
        val id: String,
        val title: String,
        val instruction: String
    )
}