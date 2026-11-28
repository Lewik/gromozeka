package com.gromozeka.infrastructure.ai.soniox

import com.gromozeka.domain.service.LiveTranscriptionEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SonioxLiveTranscriptionProviderTest {
    @Test
    fun `configuration sends raw pcm language hints and diarization without endpointing`() {
        val configuration = SonioxLiveTranscriptionConfiguration(
            baseUrl = "https://stt-rt.eu.soniox.com",
            apiKey = "synthetic",
            model = "stt-rt-v6",
            languages = listOf("he", "en", "ru"),
            nativeTranslationLanguage = "ru",
        )

        assertEquals("wss://stt-rt.eu.soniox.com/transcribe-websocket", configuration.endpoint().toString())
        val request = Json.parseToJsonElement(configuration.startRequest()).jsonObject
        assertEquals("stt-rt-v6", request.getValue("model").jsonPrimitive.content)
        assertEquals("pcm_s16be", request.getValue("audio_format").jsonPrimitive.content)
        assertEquals("true", request.getValue("enable_speaker_diarization").jsonPrimitive.content)
        assertEquals("false", request.getValue("enable_endpoint_detection").jsonPrimitive.content)
        val translation = request.getValue("translation").jsonObject
        assertEquals("one_way", translation.getValue("type").jsonPrimitive.content)
        assertEquals("ru", translation.getValue("target_language").jsonPrimitive.content)
        assertTrue(configuration.requestHeaders().getValue("Authorization").startsWith("Bearer "))
    }

    @Test
    fun `provisional text is replaced by one immutable speaker final`() {
        val assembler = SonioxTranscriptAssembler(maximumSegmentMillis = 8_000)
        val first = assembler.accept(listOf(
            token(" Hel", false, 0, 200, "1"),
            token("lo", false, 200, 400, "1"),
        ))
        assertIs<LiveTranscriptionEvent.Committed>(first[0])
        assertEquals(" Hello", assertIs<LiveTranscriptionEvent.Transcript>(first[1]).text)
        assertEquals(false, assertIs<LiveTranscriptionEvent.Transcript>(first[1]).isFinal)

        val completed = assembler.accept(listOf(
            token("Hello", true, 0, 400, "1"),
            token(".", true, 400, 500, "1"),
        ))
        val final = assertIs<LiveTranscriptionEvent.Transcript>(completed.single())
        assertEquals("Hello.", final.text)
        assertTrue(final.isFinal)
        assertEquals("1", final.speakerId)
        assertEquals("en", final.language)
        assertEquals(0L, final.startMillis)
        assertEquals(500L, final.endMillis)
        assertTrue(assembler.finish().isEmpty())
    }

    @Test
    fun `provisional speaker label may stabilize before finalization`() {
        val assembler = SonioxTranscriptAssembler()
        assembler.accept(listOf(token("draft", false, 0, 300, "1")))
        val events = assembler.accept(listOf(
            token("final", true, 0, 300, "2"),
            token(".", true, 300, 400, "2"),
        ))
        assertEquals("2", events.filterIsInstance<LiveTranscriptionEvent.Transcript>().single { it.isFinal }.speakerId)
    }

    @Test
    fun `speaker change creates ordered final segments without endpoint controls`() {
        val assembler = SonioxTranscriptAssembler(maximumSegmentMillis = 8_000)
        val events = assembler.accept(listOf(
            token("First", true, 0, 300, "1"),
            token(" speaker", true, 300, 700, "1"),
            token("Second", true, 800, 1_100, "2"),
            token(" speaker", true, 1_100, 1_500, "2"),
            token(".", true, 1_500, 1_600, "2"),
        ))
        val commits = events.filterIsInstance<LiveTranscriptionEvent.Committed>()
        val finals = events.filterIsInstance<LiveTranscriptionEvent.Transcript>().filter { it.isFinal }
        assertEquals(listOf("soniox-0", "soniox-1"), commits.map { it.itemId })
        assertEquals(listOf(null, "soniox-0"), commits.map { it.previousItemId })
        assertEquals(listOf("First speaker", "Second speaker."), finals.map { it.text })
        assertEquals(listOf("1", "2"), finals.map { it.speakerId })
    }

    @Test
    fun `long finalized speech is segmented without forcing provider finalization`() {
        val assembler = SonioxTranscriptAssembler(maximumSegmentMillis = 1_000)
        val events = assembler.accept(listOf(
            token("one ", true, 0, 400, "1"),
            token("two ", true, 400, 800, "1"),
            token("three", true, 800, 1_200, "1"),
            token(" four", true, 1_200, 1_600, "1"),
        )) + assembler.finish()
        assertEquals(listOf("one two three", " four"),
            events.filterIsInstance<LiveTranscriptionEvent.Transcript>().filter { it.isFinal }.map { it.text })
    }

    @Test
    fun `a provisional hypothesis that disappears closes its committed item with an empty final`() {
        val assembler = SonioxTranscriptAssembler()
        assembler.accept(listOf(token("wrong", false, 0, 300, "1")))
        assembler.accept(emptyList())
        val final = assertIs<LiveTranscriptionEvent.Transcript>(assembler.finish().single())
        assertTrue(final.isFinal)
        assertEquals("", final.text)
    }

    @Test
    fun `a vanished provisional source does not create an orphan native translation`() {
        val assembler = SonioxTranscriptAssembler(nativeTranslationLanguage = "ru")
        assembler.accept(listOf(token("wrong", false, 0, 300, "1", language = "he", translationStatus = "original")))
        assembler.accept(emptyList())
        val finished = assembler.finish()
        assertEquals(1, finished.filterIsInstance<LiveTranscriptionEvent.Transcript>().size)
        assertTrue(finished.none { it is LiveTranscriptionEvent.Translation })
    }

    @Test
    fun `duration segmentation waits for a word boundary instead of splitting subword tokens`() {
        val assembler = SonioxTranscriptAssembler(maximumSegmentMillis = 500)
        val events = assembler.accept(listOf(
            token("art", true, 0, 300, "1"),
            token("ific", true, 300, 600, "1"),
            token("ial", true, 600, 900, "1"),
            token(" intelligence", true, 900, 1_200, "1"),
        )) + assembler.finish()
        assertEquals(listOf("artificial", " intelligence"),
            events.filterIsInstance<LiveTranscriptionEvent.Transcript>().filter { it.isFinal }.map { it.text })
    }

    @Test
    fun `native final does not republish the previous provisional source as another segment`() {
        val assembler = SonioxTranscriptAssembler(nativeTranslationLanguage = "ru")
        assembler.accept(listOf(
            token("Hel", false, 0, 200, "1", language = "en", translationStatus = "original"),
        ))

        val completed = assembler.accept(listOf(
            token("Hello", true, 0, 400, "1", language = "en", translationStatus = "original"),
            token(".", true, 400, 500, "1", language = "en", translationStatus = "original"),
        ))

        assertTrue(completed.none { it is LiveTranscriptionEvent.Committed })
        assertEquals(
            listOf("Hello."),
            completed.filterIsInstance<LiveTranscriptionEvent.Transcript>().filter { it.isFinal }.map { it.text },
        )
    }

    @Test
    fun `mixed source languages keep native translation in source order`() {
        val assembler = SonioxTranscriptAssembler(nativeTranslationLanguage = "en")
        val source = assembler.accept(listOf(
            token("hola ", true, 0, 300, "1", language = "es", translationStatus = "original"),
            token("world", true, 300, 600, "1", language = "en", translationStatus = "original"),
            token(".", true, 600, 700, "1", language = "en", translationStatus = "original"),
        ))
        assertTrue(source.none { it is LiveTranscriptionEvent.Translation })
        assertEquals(
            null,
            source.filterIsInstance<LiveTranscriptionEvent.Transcript>().single { it.isFinal }.language,
        )

        val translated = assembler.accept(listOf(
            token("hello ", true, speaker = "1", language = "en", sourceLanguage = "es", translationStatus = "translation"),
        )).filterIsInstance<LiveTranscriptionEvent.Translation>()

        assertEquals("hello world.", translated.last().text)
        assertEquals(null, translated.last().sourceLanguage)
        assertTrue(translated.last().isFinal)
        assertTrue(assembler.finish().isEmpty())
    }

    @Test
    fun `native translation is bound to the diarized source segment and finalized after it`() {
        val assembler = SonioxTranscriptAssembler(nativeTranslationLanguage = "ru")
        val source = assembler.accept(listOf(
            token("Hello", true, 0, 300, "2", language = "en", translationStatus = "original"),
            token(".", true, 300, 400, "2", language = "en", translationStatus = "original"),
        ))
        assertEquals("Hello.", source.filterIsInstance<LiveTranscriptionEvent.Transcript>().single { it.isFinal }.text)
        assertTrue(source.none { it is LiveTranscriptionEvent.Translation })

        val translated = assembler.accept(listOf(
            token("При", true, speaker = "2", language = "ru", sourceLanguage = "en", translationStatus = "translation"),
            token("вет.", true, speaker = "2", language = "ru", sourceLanguage = "en", translationStatus = "translation"),
        )).filterIsInstance<LiveTranscriptionEvent.Translation>()
        val final = translated.last()
        assertEquals("soniox-0", final.itemId)
        assertEquals("Привет.", final.text)
        assertTrue(final.isFinal)
        assertEquals("ru", final.targetLanguage)
        assertEquals("en", final.sourceLanguage)
        assertEquals("2", final.speakerId)
        assertTrue(assembler.finish().isEmpty())
    }

    @Test
    fun `speech already in target language is passed through without waiting for translation tokens`() {
        val events = SonioxTranscriptAssembler(nativeTranslationLanguage = "en").accept(listOf(
            token("Already", true, 0, 300, "1", language = "en", translationStatus = "original"),
            token(" English.", true, 300, 600, "1", language = "en", translationStatus = "original"),
        ))
        val translation = events.filterIsInstance<LiveTranscriptionEvent.Translation>().single()
        assertEquals("Already English.", translation.text)
        assertTrue(translation.isFinal)
    }

    @Test
    fun `provisional native translation is replaced before the immutable final`() {
        val assembler = SonioxTranscriptAssembler(nativeTranslationLanguage = "ru")
        assembler.accept(listOf(token("Hello", true, 0, 300, "1", language = "en", translationStatus = "original")))
        val partial = assembler.accept(listOf(token("При", false, speaker = "1", language = "ru",
            sourceLanguage = "en", translationStatus = "translation")))
            .filterIsInstance<LiveTranscriptionEvent.Translation>().single()
        assertEquals("При", partial.text)
        assertFalse(partial.isFinal)
        val stable = assembler.accept(listOf(token("Привет", true, speaker = "1", language = "ru",
            sourceLanguage = "en", translationStatus = "translation")))
            .filterIsInstance<LiveTranscriptionEvent.Translation>()
        assertEquals("Привет", stable.last().text)
        assertFalse(stable.last().isFinal)
        val finished = assembler.finish().filterIsInstance<LiveTranscriptionEvent.Translation>().single()
        assertEquals("Привет", finished.text)
        assertTrue(finished.isFinal)
    }

    @Test
    fun `stream cannot finish while provider still has provisional tokens`() {
        val assembler = SonioxTranscriptAssembler()
        assembler.accept(listOf(token("draft", false, 0, 300, "1")))
        assertFailsWith<IllegalStateException> { assembler.finish() }
    }

    private fun token(
        text: String,
        final: Boolean,
        start: Long = 0,
        end: Long = 0,
        speaker: String,
        language: String = "en",
        sourceLanguage: String? = null,
        translationStatus: String? = null,
    ) = SonioxRealtimeToken(
        text = text,
        isFinal = final,
        startMillis = if (translationStatus == "translation") null else start,
        endMillis = if (translationStatus == "translation") null else end,
        confidence = 0.9,
        speaker = speaker,
        language = language,
        sourceLanguage = sourceLanguage,
        translationStatus = translationStatus,
    )
}
