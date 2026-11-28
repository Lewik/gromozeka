package com.gromozeka.remote.protocol

import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import com.gromozeka.domain.model.ai.AiUsage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LiveTranslationProtocolTest {
    @Test
    fun `text translation requests retain drafts language and gap metadata through the wire`() {
        val request = TranslateLiveTextRequest("logical-meeting", 42, "pt-br",
            context = listOf(LiveTranslationSourceBlock("history", "Original.", "Tradução.")),
            blocks = listOf(LiveTranslationSourceBlock("work", "שלום. Новый текст.", "Previous draft.", gapBefore = true)),
            runtimeSelection = AiRuntimeSelection(AiModelConfiguration.Id("translation-model")))
        val envelope = GromozekaClientEnvelope("request-id", request)
        assertEquals(envelope, RemoteProtocolCodec.decodeClientBinary(RemoteProtocolCodec.encodeClientBinary(envelope)))
        assertEquals(envelope, RemoteProtocolCodec.decodeClientText(RemoteProtocolCodec.encodeClientText(envelope)))
    }

    @Test
    fun `translation results retain request binding and cache usage in both encodings`() {
        val response = LiveTextTranslatedResponse(42, "ru", listOf(TranslatedLiveBlock("work", "Перевод.")),
            AiUsage(promptTokens = 20, completionTokens = 10, cacheReadTokens = 1_024))
        val envelope = GromozekaServerEnvelope("request-id", response)
        assertEquals(envelope, RemoteProtocolCodec.decodeServerBinary(RemoteProtocolCodec.encodeServerBinary(envelope)))
        assertEquals(envelope, RemoteProtocolCodec.decodeServerText(RemoteProtocolCodec.encodeServerText(envelope)))
    }

    @Test
    fun `invalid source bindings and excessive batches fail before execution`() {
        fun request(context: List<LiveTranslationSourceBlock> = emptyList(),
                    blocks: List<LiveTranslationSourceBlock> = listOf(LiveTranslationSourceBlock("work", "Original."))) =
            TranslateLiveTextRequest("meeting", 0, "ru", context, blocks)
        assertFailsWith<IllegalArgumentException> { request(blocks = emptyList()) }
        assertFailsWith<IllegalArgumentException> { request(context = listOf(LiveTranslationSourceBlock("work", "Old."))) }
        assertFailsWith<IllegalArgumentException> {
            request(blocks = (0 until 33).map { LiveTranslationSourceBlock("block-$it", "Text.") })
        }
        assertFailsWith<IllegalArgumentException> {
            request(blocks = listOf(LiveTranslationSourceBlock("one", "a".repeat(8_000)),
                LiveTranslationSourceBlock("two", "b".repeat(8_000))))
        }
        assertFailsWith<IllegalArgumentException> { TranslateLiveTextRequest("meeting", 0, "not a code", emptyList(),
            listOf(LiveTranslationSourceBlock("work", "Original."))) }
    }
}
