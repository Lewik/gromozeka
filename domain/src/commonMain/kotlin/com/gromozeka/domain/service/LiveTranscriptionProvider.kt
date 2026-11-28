package com.gromozeka.domain.service

import com.gromozeka.domain.model.ai.AiAdmissionOutcome
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import kotlinx.coroutines.flow.Flow

interface LiveTranscriptionProvider {
    suspend fun open(
        userStableId: String,
        selection: AiRuntimeSelection,
        languages: List<String>,
        manualTurnControl: Boolean = false,
    ): LiveTranscriptionSession

    suspend fun open(
        userStableId: String,
        selection: AiRuntimeSelection,
        languages: List<String>,
        manualTurnControl: Boolean,
        nativeTranslationLanguage: String?,
    ): LiveTranscriptionSession {
        require(nativeTranslationLanguage == null) { "The selected Live provider does not support native translation" }
        return open(userStableId, selection, languages, manualTurnControl)
    }
}

enum class LiveTranscriptionTurnDetection { CLIENT, PROVIDER }

interface LiveTranscriptionSession {
    val events: Flow<LiveTranscriptionEvent>
    val turnDetection: LiveTranscriptionTurnDetection

    /** The client microphone supplies mono PCM16, 16 kHz, big endian. */
    suspend fun appendAudio(pcm: ByteArray)
    suspend fun commit()
    /** Flushes pending audio and completes events after all final transcripts. */
    suspend fun finishInput()
    suspend fun close(outcome: AiAdmissionOutcome = AiAdmissionOutcome.CANCELLED)
}

sealed interface LiveTranscriptionEvent {
    data class Committed(val itemId: String, val previousItemId: String?) : LiveTranscriptionEvent
    data class Transcript(
        val itemId: String,
        val text: String,
        val isFinal: Boolean,
        val speakerId: String? = null,
        val language: String? = null,
        val startMillis: Long? = null,
        val endMillis: Long? = null,
    ) : LiveTranscriptionEvent {
        init {
            require(speakerId == null || speakerId.isNotBlank() && speakerId.length <= 64) {
                "Live transcript speaker id is invalid"
            }
            require(language == null || language.matches(Regex("[a-z]{2,3}(-[a-z0-9]{2,8})*"))) {
                "Live transcript language is invalid"
            }
            require((startMillis == null) == (endMillis == null) &&
                (startMillis == null || startMillis >= 0 && endMillis!! >= startMillis)) {
                "Live transcript audio range is invalid"
            }
        }
    }

    data class Translation(
        val itemId: String,
        val text: String,
        val isFinal: Boolean,
        val targetLanguage: String,
        val sourceLanguage: String? = null,
        val speakerId: String? = null,
    ) : LiveTranscriptionEvent {
        init {
            require(itemId.isNotBlank()) { "Live native translation item id is invalid" }
            require(targetLanguage.matches(Regex("[a-z]{2,3}(-[a-z0-9]{2,8})*"))) {
                "Live native translation target language is invalid"
            }
            require(sourceLanguage == null || sourceLanguage.matches(Regex("[a-z]{2,3}(-[a-z0-9]{2,8})*"))) {
                "Live native translation source language is invalid"
            }
            require(speakerId == null || speakerId.isNotBlank() && speakerId.length <= 64) {
                "Live native translation speaker id is invalid"
            }
        }
    }
}

class LiveTranscriptionException(
    message: String,
    val retryable: Boolean,
    cause: Throwable? = null,
) : Exception(message, cause)
