package com.gromozeka.infrastructure.ai.openai

import com.gromozeka.domain.model.ai.AiAdmissionOutcome
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.service.LiveTranscriptionEvent
import com.gromozeka.domain.service.LiveTranscriptionTurnDetection
import com.gromozeka.infrastructure.ai.runtime.TestAiAdmission
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

class OpenAiLiveTranscriptionLiveTest {
    @Test
    fun `synthetic multilingual speech receives native partials and committed originals`() = runBlocking<Unit> {
        assumeTrue("Opt-in real OpenAI audio evaluation", System.getenv("GROMOZEKA_OPENAI_LIVE_SMOKE") == "true")
        val key = requireNotNull(System.getenv("OPENAI_API_KEY")) { "OPENAI_API_KEY is required" }
        require(key.isNotBlank()) { "OPENAI_API_KEY is empty" }
        val audioDirectory = Path.of(requireNotNull(System.getenv("GROMOZEKA_OPENAI_LIVE_AUDIO_DIR")) {
            "GROMOZEKA_OPENAI_LIVE_AUDIO_DIR must contain synthetic en.pcm, he.pcm and ru.pcm (mono PCM16BE, 16 kHz)"
        })
        val languages = System.getenv("GROMOZEKA_OPENAI_LIVE_HINTS")
            ?.split(',')?.map(String::trim)?.filter(String::isNotEmpty) ?: listOf("he", "en", "ru")
        val fixtures = listOf(
            "en" to listOf("blue", "notebook"),
            "he" to listOf("מחברת", "שולחן"),
            "ru" to listOf("тетрад", "стол"),
        ).map { (language, words) ->
            val bytes = Files.readAllBytes(audioDirectory.resolve("$language.pcm"))
            require(bytes.isNotEmpty() && bytes.size % 2 == 0 && bytes.size <= 960_000) {
                "Synthetic $language audio must be complete PCM16 samples and at most 30 seconds"
            }
            Triple(language, words, bytes)
        }
        val providerModel = System.getenv("GROMOZEKA_OPENAI_LIVE_MODEL") ?: "gpt-live-transcribe"
        require(providerModel in setOf("gpt-live-transcribe", "gpt-transcribe")) { "Unsupported Live smoke model" }
        println("LIVE_ASR_CONFIG model=$providerModel hints=$languages semantic_vad_low=${providerModel == "gpt-transcribe"}")
        val admission = TestAiAdmission()
        val model = AiModelConfiguration(
            id = AiModelConfiguration.Id("synthetic-openai-live-smoke"),
            connectionId = AiConnection.Id("synthetic-openai-live-smoke"),
            providerModelId = providerModel,
            displayName = "Synthetic multilingual smoke",
        )
        val session = OpenAiLiveTranscriptionSession(
            OpenAiLiveTranscriptionConfiguration("https://api.openai.com/v1", key, model.providerModelId, languages),
            "synthetic-openai-live-smoke",
            admission.openSession(model, "LIVE_TRANSCRIPTION_SMOKE"),
        )
        var succeeded = false
        try {
            withTimeout(120_000) {
                session.start()
                val started = TimeSource.Monotonic.markNow()
                val observed = mutableListOf<LiveTranscriptionEvent>()
                var requestedCommits = 0
                var partialsBeforeFirstCommit = 0
                coroutineScope {
                    val collector = launch {
                        session.events.collect { event ->
                            observed += event
                            if (event is LiveTranscriptionEvent.Transcript) {
                                if (!event.isFinal && requestedCommits == 0 && observed.none { it is LiveTranscriptionEvent.Committed }) partialsBeforeFirstCommit++
                                if (event.isFinal) println("LIVE_ASR_FINAL elapsed_ms=${started.elapsedNow().inWholeMilliseconds} text=${event.text}")
                            }
                        }
                    }
                    for ((index, fixture) in fixtures.withIndex()) {
                        val (language, _, pcm) = fixture
                        var offset = 0
                        while (offset < pcm.size) {
                            val end = minOf(offset + 3_200, pcm.size)
                            session.appendAudio(pcm.copyOfRange(offset, end))
                            delay((end - offset) * 1_000L / 32_000)
                            offset = end
                        }
                        if (session.turnDetection == LiveTranscriptionTurnDetection.CLIENT) {
                            requestedCommits++
                            session.commit()
                        } else {
                            withTimeout(12_000) {
                                while (true) {
                                    val committed = observed.filterIsInstance<LiveTranscriptionEvent.Committed>()
                                    val item = committed.getOrNull(index)
                                    if (item != null && observed.filterIsInstance<LiveTranscriptionEvent.Transcript>()
                                            .any { it.itemId == item.itemId && it.isFinal }) break
                                    session.appendAudio(ByteArray(3_200))
                                    delay(100)
                                }
                            }
                        }
                        println("LIVE_ASR_TURN language=$language detection=${session.turnDetection} elapsed_ms=${started.elapsedNow().inWholeMilliseconds}")
                    }
                    session.finishInput()
                    collector.join()
                }
                val commits = observed.filterIsInstance<LiveTranscriptionEvent.Committed>()
                val finals = observed.filterIsInstance<LiveTranscriptionEvent.Transcript>().filter { it.isFinal }
                assertEquals(fixtures.size, commits.size)
                assertEquals(fixtures.size, finals.size)
                assertEquals(commits.map { it.itemId }.toSet(), finals.map { it.itemId }.toSet())
                if (providerModel == "gpt-live-transcribe") {
                    assertTrue(partialsBeforeFirstCommit > 0, "No native partial arrived while the first audio turn was still open")
                } else {
                    assertEquals(0, partialsBeforeFirstCommit, "Committed-turn transcription unexpectedly ran before the first commit")
                }
                assertTrue(observed.filterIsInstance<LiveTranscriptionEvent.Transcript>().any { !it.isFinal })
                println("LIVE_ASR_PROTOCOL_OK model=$providerModel hints=$languages turns=${finals.size} partials_before_first_commit=$partialsBeforeFirstCommit")
                val finalById = finals.associateBy { it.itemId }
                val qualityFindings = fixtures.zip(commits).mapNotNull { (fixture, commit) ->
                    val (language, expectedWords, _) = fixture
                    val original = finalById.getValue(commit.itemId).text.lowercase()
                    val missingWords = expectedWords.filter { it !in original }
                    if (missingWords.isEmpty()) null else "$language missing=$missingWords original=$original"
                }
                assertTrue(qualityFindings.isEmpty(), qualityFindings.joinToString("\n"))
                println("LIVE_ASR_QUALITY_OK hints=$languages")
                succeeded = true
            }
        } finally {
            session.close(if (succeeded) AiAdmissionOutcome.SUCCEEDED else AiAdmissionOutcome.FAILED)
        }
        assertTrue(admission.active.isEmpty())
        assertEquals(AiAdmissionOutcome.SUCCEEDED, admission.completed.single().second)
    }
}
