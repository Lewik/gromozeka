package com.gromozeka.infrastructure.ai.openai

import com.gromozeka.domain.model.AppMode
import com.gromozeka.domain.model.AiProvider
import com.gromozeka.domain.model.SecretRef
import com.gromozeka.domain.model.UserDeviceSettings
import com.gromozeka.domain.model.UserProfile
import com.gromozeka.domain.model.ai.AiAdmissionOutcome
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiModelCapability
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiModelSpec
import com.gromozeka.domain.service.LiveTranscriptionEvent
import com.gromozeka.domain.service.ResolvedAiRuntime
import com.gromozeka.domain.service.SettingsProvider
import com.gromozeka.infrastructure.ai.runtime.SdkHttpClientPool
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
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

class OpenAiWhisperWindowedLiveTest {
    private val httpClients = SdkHttpClientPool()

    @AfterTest
    fun closeHttpClients() = httpClients.close()

    @Test
    fun `real Whisper windows reconcile a natural recording through the production adapter`() = runBlocking {
        assumeTrue(
            "Opt-in real OpenAI Whisper window evaluation",
            System.getenv("GROMOZEKA_OPENAI_WHISPER_WINDOW_SMOKE") == "true",
        )
        val audioFile = File(requireNotNull(System.getenv("GROMOZEKA_OPENAI_WHISPER_WINDOW_AUDIO")) {
            "GROMOZEKA_OPENAI_WHISPER_WINDOW_AUDIO must point to a natural audio recording"
        })
        require(audioFile.isFile) { "Whisper window audio does not exist: $audioFile" }
        val pcmBigEndian = decodePcm16BigEndian(audioFile)
        require(pcmBigEndian.size >= 21 * 16_000 * 2) { "Whisper window smoke requires at least 21 seconds" }
        require(pcmBigEndian.size <= 5 * 60 * 16_000 * 2) { "Whisper window smoke is limited to five minutes" }

        val languageHints = System.getenv("GROMOZEKA_OPENAI_WHISPER_WINDOW_HINTS")
            ?.split(',')?.map(String::trim)?.filter(String::isNotEmpty) ?: emptyList()
        val connection = AiConnection.OpenAiApi(
            id = AiConnection.Id("openai-whisper-window-smoke"),
            displayName = "OpenAI Whisper window smoke",
            enabled = true,
            apiKey = SecretRef.EnvironmentVariable("OPENAI_API_KEY"),
        )
        val configuration = AiModelConfiguration(
            id = AiModelConfiguration.Id("openai-whisper-window-smoke"),
            connectionId = connection.id,
            providerModelId = "whisper-1",
            displayName = "OpenAI Whisper window smoke",
        )
        val runtime = ResolvedAiRuntime(
            connection,
            configuration,
            AiModelSpec(
                id = "whisper-1",
                provider = AiProvider.OPENAI,
                capabilities = setOf(AiModelCapability.SPEECH_TO_TEXT, AiModelCapability.REALTIME_TRANSCRIPTION),
            ),
        )
        val admission = TestAiAdmission()
        val productionTranscriber = OpenAiWhisperWindowTranscriber(
            runtime,
            OpenAiSdkClientFactory(EnvironmentSettingsProvider, httpClients),
            admission,
            languageHints,
        )
        val transcriber = WhisperWindowTranscriber { startSample, pcm ->
            println("WHISPER_WINDOW_REQUEST start_seconds=${startSample / 16_000.0} duration_seconds=${pcm.size / 32_000.0}")
            productionTranscriber.transcribe(startSample, pcm)
        }
        val windowSeconds = System.getenv("GROMOZEKA_OPENAI_WHISPER_WINDOW_SECONDS")?.toInt() ?: 20
        require(windowSeconds in 8..30 && windowSeconds % 2 == 0) {
            "Whisper evaluation window must be an even number of seconds from 8 to 30"
        }
        val windowing = WhisperWindowing(
            windowSamples = windowSeconds.toLong() * 16_000,
            hopSamples = windowSeconds.toLong() * 8_000,
        )
        val session = OpenAiWhisperWindowedTranscriptionSession(
            transcriber,
            settings = windowing,
            reconciler = WhisperWindowReconciler(windowing),
        )
        session.start()
        val started = TimeSource.Monotonic.markNow()
        val observed = async {
            session.events.onEach { event ->
                if (event is LiveTranscriptionEvent.Transcript && event.isFinal) {
                    println("WHISPER_WINDOW_FINAL elapsed_ms=${started.elapsedNow().inWholeMilliseconds} text=${event.text}")
                }
            }.toList()
        }
        val playbackSpeed = System.getenv("GROMOZEKA_OPENAI_WHISPER_WINDOW_PLAYBACK_SPEED")
            ?.toDouble()?.also { require(it > 0.0) { "Playback speed must be positive" } }

        withTimeout(5 * 60_000L) {
            var offset = 0
            while (offset < pcmBigEndian.size) {
                val end = minOf(offset + 32_000, pcmBigEndian.size)
                session.appendAudio(pcmBigEndian.copyOfRange(offset, end))
                if (playbackSpeed != null) delay(((end - offset) * 1_000.0 / 32_000.0 / playbackSpeed).toLong())
                offset = end
            }
            session.finishInput()
        }

        val events = observed.await()
        val commits = events.filterIsInstance<LiveTranscriptionEvent.Committed>()
        val finals = events.filterIsInstance<LiveTranscriptionEvent.Transcript>().filter { it.isFinal }
        assertTrue(finals.isNotEmpty(), "Whisper window smoke returned no final text")
        assertEquals(commits.map { it.itemId }, finals.map { it.itemId })
        assertEquals(List(commits.size) { index -> commits.getOrNull(index - 1)?.itemId }, commits.map { it.previousItemId })
        assertTrue(admission.active.isEmpty())
        assertTrue(admission.completed.isNotEmpty())
        assertTrue(admission.completed.all { it.second == AiAdmissionOutcome.SUCCEEDED })
        System.getenv("GROMOZEKA_OPENAI_WHISPER_WINDOW_EXPECTED_REQUESTS")?.toInt()?.let { expectedRequests ->
            assertEquals(expectedRequests, admission.completed.size, "Unexpected normal/repair request count")
        }
        val text = finals.joinToString(" ") { it.text }.trim()
        assertTrue(text.isNotEmpty())
        val expected = System.getenv("GROMOZEKA_OPENAI_WHISPER_WINDOW_EXPECTED")
            ?.split(',')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()
        val missing = expected.filter { it.lowercase() !in text.lowercase() }
        assertTrue(missing.isEmpty(), "Whisper window smoke missing=$missing text=$text")
        println("WHISPER_WINDOW_PROTOCOL_OK audio_seconds=${pcmBigEndian.size / 32_000.0} window_seconds=$windowSeconds requests=${admission.completed.size} finals=${finals.size}")
        println("WHISPER_WINDOW_TEXT $text")
        session.close(AiAdmissionOutcome.SUCCEEDED)
    }

    private fun decodePcm16BigEndian(file: File): ByteArray = AudioSystem.getAudioInputStream(file).use { source ->
        val targetFormat = AudioFormat(16_000f, 16, 1, true, true)
        AudioSystem.getAudioInputStream(targetFormat, source).use { converted -> converted.readAllBytes() }
    }

    private object EnvironmentSettingsProvider : SettingsProvider {
        override val userProfile: UserProfile = UserProfile()
        override val userDeviceSettings: UserDeviceSettings = UserDeviceSettings.Desktop()
        override val mode: AppMode = AppMode.TEST
        override val homeDirectory: String = System.getProperty("java.io.tmpdir")

        override fun resolveSecret(secretRef: SecretRef?): String? = when (secretRef) {
            is SecretRef.EnvironmentVariable -> System.getenv(secretRef.name)
            is SecretRef.Inline -> secretRef.value
            is SecretRef.File -> File(secretRef.path).takeIf(File::isFile)?.readText()
            null -> null
        }
    }
}
