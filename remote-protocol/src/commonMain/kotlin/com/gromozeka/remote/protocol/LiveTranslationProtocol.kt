package com.gromozeka.remote.protocol

import com.gromozeka.domain.model.ai.AiRuntimeSelection
import com.gromozeka.domain.model.ai.AiUsage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private val liveTextIdPattern = Regex("[A-Za-z0-9._:-]{1,128}")
private val liveTextLanguagePattern = Regex("[a-z]{2,3}(-[a-z0-9]{2,8})*")

@Serializable
data class LiveTranslationSourceBlock(
    val id: String,
    val text: String,
    val previousTranslation: String? = null,
    val gapBefore: Boolean = false,
) {
    init {
        require(liveTextIdPattern.matches(id)) { "Invalid Live translation block id" }
        require(text.isNotBlank() && text.length <= 12_000) { "Invalid Live translation source block size" }
        require(previousTranslation == null || previousTranslation.length <= 64_000) {
            "Live previous translation is too large"
        }
    }
}

@Serializable
@SerialName("translate_live_text")
data class TranslateLiveTextRequest(
    val sessionId: String,
    val revision: Int,
    val targetLanguage: String,
    val context: List<LiveTranslationSourceBlock>,
    val blocks: List<LiveTranslationSourceBlock>,
    val runtimeSelection: AiRuntimeSelection? = null,
) : ClientRequest {
    init {
        require(liveTextIdPattern.matches(sessionId)) { "Invalid Live translation session id" }
        require(revision >= 0) { "Invalid Live translation revision" }
        require(liveTextLanguagePattern.matches(targetLanguage)) { "Invalid Live translation language" }
        require(context.size <= 1_024 && blocks.size in 1..32) { "Invalid Live translation block count" }
        val allBlocks = context + blocks
        require(allBlocks.map { it.id }.distinct().size == allBlocks.size) { "Duplicate Live translation block ids" }
        require(context.sumOf { it.text.length + (it.previousTranslation?.length ?: 0) } <= 200_000) {
            "Live translation context is too large"
        }
        require(blocks.sumOf { it.text.length } <= 12_000) { "Live translation batch is too large" }
        require(allBlocks.sumOf { it.text.length + (it.previousTranslation?.length ?: 0) } <= 224_000) {
            "Live translation input is too large"
        }
    }
}

@Serializable
data class TranslatedLiveBlock(val id: String, val text: String) {
    init {
        require(liveTextIdPattern.matches(id)) { "Invalid translated Live block id" }
        require(text.isNotBlank() && text.length <= 64_000) { "Invalid translated Live block size" }
    }
}

@Serializable
@SerialName("live_text_translated")
data class LiveTextTranslatedResponse(
    val revision: Int,
    val targetLanguage: String,
    val blocks: List<TranslatedLiveBlock>,
    val usage: AiUsage? = null,
) : ServerResponse
