package com.gromozeka.infrastructure.ai.soniox

import com.gromozeka.domain.model.ai.AiAdmissionOutcome
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.service.LiveTranscriptionEvent
import com.gromozeka.infrastructure.ai.runtime.TestAiAdmission
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import java.io.File
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

class SonioxLiveTranscriptionLiveTest {
    @Test
    fun `real Soniox v6 streams provisional text and diarized finals`() = runBlocking {
        assumeTrue("Opt-in real Soniox evaluation", System.getenv("GROMOZEKA_SONIOX_LIVE_SMOKE") == "true")
        val key = requireNotNull(System.getenv("SONIOX_API_KEY")) { "SONIOX_API_KEY is required" }
        val file = File(requireNotNull(System.getenv("GROMOZEKA_SONIOX_LIVE_AUDIO")) {
            "GROMOZEKA_SONIOX_LIVE_AUDIO must point to a representative recording"
        })
        require(file.isFile) { "Soniox smoke audio does not exist: $file" }
        val pcm = decodePcm16BigEndian(file)
        require(pcm.size in 32_000..(5 * 60 * 32_000)) { "Soniox smoke audio must be 1 second to 5 minutes" }
        val languages = System.getenv("GROMOZEKA_SONIOX_LIVE_HINTS")
            ?.split(',')?.map(String::trim)?.filter(String::isNotEmpty) ?: listOf("he", "en", "ru")
        val minimumSpeakers = System.getenv("GROMOZEKA_SONIOX_LIVE_MIN_SPEAKERS")?.toInt() ?: 1
        val expected = System.getenv("GROMOZEKA_SONIOX_LIVE_EXPECTED")
            ?.split(',')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()
        val translationLanguage = System.getenv("GROMOZEKA_SONIOX_LIVE_TRANSLATE_TO")?.trim()?.ifEmpty { null }
        val expectedTranslation = System.getenv("GROMOZEKA_SONIOX_LIVE_EXPECTED_TRANSLATION")
            ?.split(',')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()
        val admission = TestAiAdmission()
        val model = AiModelConfiguration(
            id = AiModelConfiguration.Id("soniox-live-smoke"),
            connectionId = AiConnection.Id("soniox-live-smoke"),
            providerModelId = "stt-rt-v6",
            displayName = "Soniox Live smoke",
        )
        val session = SonioxLiveTranscriptionSession(
            SonioxLiveTranscriptionConfiguration(
                baseUrl = "https://stt-rt.soniox.com/transcribe-websocket",
                apiKey = key,
                model = model.providerModelId,
                languages = languages,
                nativeTranslationLanguage = translationLanguage,
            ),
            admission.openSession(model, "LIVE_TRANSCRIPTION_SMOKE"),
        )
        var succeeded = false
        try {
            withTimeout(6 * 60_000L) {
                session.start()
                val started = TimeSource.Monotonic.markNow()
                val observed = async {
                    session.events.onEach { event ->
                        when (event) {
                            is LiveTranscriptionEvent.Transcript -> if (event.isFinal) {
                                println("SONIOX_FINAL elapsed_ms=${started.elapsedNow().inWholeMilliseconds} speaker=${event.speakerId} language=${event.language} text=${event.text}")
                            }
                            is LiveTranscriptionEvent.Translation -> if (event.isFinal) {
                                println("SONIOX_TRANSLATION elapsed_ms=${started.elapsedNow().inWholeMilliseconds} speaker=${event.speakerId} source=${event.sourceLanguage} target=${event.targetLanguage} text=${event.text}")
                            }
                            is LiveTranscriptionEvent.Committed -> Unit
                        }
                    }.toList()
                }
                var offset = 0
                while (offset < pcm.size) {
                    val end = minOf(offset + 3_200, pcm.size)
                    session.appendAudio(pcm.copyOfRange(offset, end))
                    delay((end - offset) * 1_000L / 32_000)
                    offset = end
                }
                session.finishInput()
                val events = observed.await()
                val commits = events.filterIsInstance<LiveTranscriptionEvent.Committed>()
                val transcripts = events.filterIsInstance<LiveTranscriptionEvent.Transcript>()
                val finals = transcripts.filter { it.isFinal }
                assertTrue(transcripts.any { !it.isFinal }, "Soniox returned no provisional text")
                assertTrue(finals.isNotEmpty(), "Soniox returned no final transcript")
                assertEquals(commits.map { it.itemId }, finals.map { it.itemId })
                assertEquals(List(commits.size) { index -> commits.getOrNull(index - 1)?.itemId }, commits.map { it.previousItemId })
                val visibleFinals = finals.filter { it.text.isNotBlank() }
                assertTrue(visibleFinals.all { it.startMillis != null && it.endMillis != null && it.language != null && it.speakerId != null })
                val speakers = visibleFinals.mapNotNull { it.speakerId }.toSet()
                assertTrue(speakers.size >= minimumSpeakers, "Soniox speakers=$speakers expected at least $minimumSpeakers")
                val text = finals.joinToString(" ") { it.text }.lowercase()
                val missing = expected.filter { it.lowercase() !in text }
                assertTrue(missing.isEmpty(), "Soniox missing=$missing text=$text")
                if (translationLanguage != null) {
                    val translations = events.filterIsInstance<LiveTranscriptionEvent.Translation>()
                    val translatedFinals = translations.filter { it.isFinal }
                    assertEquals(visibleFinals.map { it.itemId }, translatedFinals.map { it.itemId })
                    assertTrue(translatedFinals.all { it.targetLanguage == translationLanguage && it.speakerId != null })
                    val translatedText = translatedFinals.joinToString(" ") { it.text }.lowercase()
                    val missingTranslation = expectedTranslation.filter { it.lowercase() !in translatedText }
                    assertTrue(missingTranslation.isEmpty(), "Soniox translation missing=$missingTranslation text=$translatedText")
                }
                println("SONIOX_PROTOCOL_OK audio_seconds=${pcm.size / 32_000.0} speakers=$speakers finals=${finals.size} partials=${transcripts.count { !it.isFinal }} translation=$translationLanguage")
                succeeded = true
            }
        } finally {
            session.close(if (succeeded) AiAdmissionOutcome.SUCCEEDED else AiAdmissionOutcome.FAILED)
        }
        assertTrue(admission.active.isEmpty())
        assertEquals(AiAdmissionOutcome.SUCCEEDED, admission.completed.single().second)
    }

    private fun decodePcm16BigEndian(file: File): ByteArray = AudioSystem.getAudioInputStream(file).use { source ->
        val target = AudioFormat(16_000f, 16, 1, true, true)
        AudioSystem.getAudioInputStream(target, source).use { it.readAllBytes() }
    }
}
