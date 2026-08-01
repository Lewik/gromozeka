package com.gromozeka.domain.model

import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

@Serializable
sealed interface SpeechAudioSource {
    @Serializable
    @SerialName("current_client")
    data object CurrentClient : SpeechAudioSource

    @Serializable
    @SerialName("worker_input")
    data class WorkerInput(
        val workerId: ConversationRuntimeWorkerId,
        val inputId: WorkerAudioInput.Id,
    ) : SpeechAudioSource
}

@Serializable
data class WorkerAudioInput(
    val id: Id,
    val displayName: String,
    val isDefault: Boolean = false,
) {
    init {
        require(displayName.isNotBlank()) { "Worker audio input display name must not be blank" }
    }

    @Serializable
    @JvmInline
    value class Id(val value: String) {
        init {
            require(value.isNotBlank()) { "Worker audio input id must not be blank" }
        }
    }

    companion object {
        val SystemDefault = WorkerAudioInput(
            id = Id("system-default"),
            displayName = "System default microphone",
            isDefault = true,
        )
    }
}
