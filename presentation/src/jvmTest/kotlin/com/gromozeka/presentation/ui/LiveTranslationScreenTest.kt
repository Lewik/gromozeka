package com.gromozeka.presentation.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.gromozeka.client.LiveInterpreterClient
import com.gromozeka.client.LiveInterpreterRemoteSession
import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.AiProvider
import com.gromozeka.domain.model.SecretRef
import com.gromozeka.domain.model.ai.AiCatalog
import com.gromozeka.domain.model.ai.AiCatalogSnapshot
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiModelCapability
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiModelSpec
import com.gromozeka.domain.model.ai.AiRuntimeAssignment
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import com.gromozeka.domain.service.AiConfigurationProvider
import com.gromozeka.domain.service.ResolvedAiRuntime
import com.gromozeka.presentation.services.ClientLiveAudioStreamer
import com.gromozeka.presentation.services.ClientLiveAudioStreamingSession
import com.gromozeka.presentation.services.LiveInterpreterController
import com.gromozeka.presentation.services.LiveInterpreterPhase
import com.gromozeka.presentation.services.translation.LocalizedText
import com.gromozeka.presentation.services.translation.data.Translation
import com.gromozeka.remote.protocol.LiveInterpreterFailedEvent
import com.gromozeka.remote.protocol.LiveInterpreterStatusEvent
import com.gromozeka.remote.protocol.LiveInterpreterStoppedEvent
import com.gromozeka.remote.protocol.LiveInterpreterTranscriptEvent
import com.gromozeka.remote.protocol.LiveTextTranslatedResponse
import com.gromozeka.remote.protocol.RemotePcmAudioChunk
import com.gromozeka.remote.protocol.ServerPayload
import com.gromozeka.remote.protocol.TranslateLiveTextRequest
import com.gromozeka.remote.protocol.TranslatedLiveBlock
import com.gromozeka.shared.localization.BundledTranslations
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiveTranslationScreenTest {
    @Test
    fun `language multiselect stays usable during capture and added column receives the existing original`() = runComposeUiTest {
        val client = FakeClient()
        val configuration = configuration()
        lateinit var controller: LiveInterpreterController
        setContent {
            val scope = rememberCoroutineScope()
            controller = remember { LiveInterpreterController(client, FakeMicrophone(), scope,
                initialTranslationLanguages = listOf("en")) }
            DisposableEffect(controller) { onDispose { controller.cancel() } }
            TranslationProvider(Translation(BundledTranslations.english)) {
                MaterialTheme { Box(Modifier.size(1_200.dp, 800.dp)) { LiveInterpreterScreen(controller, configuration) } }
            }
        }
        runOnIdle { controller.start(AiRuntimeSelection(AiModelConfiguration.Id("ui-model"))) }
        waitUntil(timeoutMillis = 5_000) { controller.state.value.phase == LiveInterpreterPhase.LISTENING }
        val original = "Original paragraph. Another sentence in the same block."
        runOnIdle { client.output.trySend(LiveInterpreterTranscriptEvent(
            "ui-session", "turn", 0, original, true,
            speakerId = "2", language = "en", startMillis = 100, endMillis = 1_500,
        )) }
        waitUntil(timeoutMillis = 5_000) { controller.translations.state.value.columns["en"]?.values?.isNotEmpty() == true }
        onNodeWithText("Translate into: en").performClick()
        onNodeWithText("(he)", substring = true).performClick()
        waitUntil(timeoutMillis = 5_000) { controller.translations.state.value.columns["he"]?.values?.isNotEmpty() == true }
        runOnIdle {
            assertEquals(LiveInterpreterPhase.LISTENING, controller.state.value.phase)
            assertEquals(listOf("en", "he"), controller.translations.state.value.languages)
            assertTrue(client.requests.any { it.targetLanguage == "he" && it.blocks.single().text == original })
        }
        // Close the popup before asserting content in the main semantics tree.
        onNodeWithText("Translate into: en, he").performClick()
        onNodeWithText("he:$original").assertExists()
        onNodeWithText("en:$original").assertExists()
        onNodeWithText(original).assertExists()
        onNodeWithText("Speaker 2").assertExists()
    }

    @Test
    fun `paused session freezes its model and exposes Stop beside Resume`() = runComposeUiTest {
        val client = FakeClient()
        val configuration = configuration()
        lateinit var controller: LiveInterpreterController
        setContent {
            val scope = rememberCoroutineScope()
            controller = remember { LiveInterpreterController(client, FakeMicrophone(), scope) }
            DisposableEffect(controller) { onDispose { controller.cancel() } }
            TranslationProvider(Translation(BundledTranslations.english)) {
                MaterialTheme { Box(Modifier.size(1_200.dp, 800.dp)) { LiveInterpreterScreen(controller, configuration) } }
            }
        }

        onNodeWithText("Start").assertIsEnabled().performClick()
        waitUntil(timeoutMillis = 5_000) { controller.state.value.phase == LiveInterpreterPhase.LISTENING }
        runOnIdle {
            client.output.trySend(LiveInterpreterFailedEvent("ui-session", "Synthetic failure", retryable = false))
        }
        waitUntil(timeoutMillis = 5_000) { controller.state.value.phase == LiveInterpreterPhase.PAUSED }

        onNodeWithText("Transcription model: UI model").assertIsNotEnabled()
        onNodeWithText("Resume").assertIsEnabled()
        onNodeWithText("Stop").assertIsEnabled().performClick()
        waitUntil(timeoutMillis = 5_000) { controller.state.value.phase == LiveInterpreterPhase.STOPPED }
        onNodeWithText("Transcription model: UI model").assertIsEnabled()
        onNodeWithText("Start").assertIsEnabled()
    }

    @Test
    fun `Soniox Live 2 is selectable`() = runComposeUiTest {
        val configuration = configuration(soniox = true)
        lateinit var controller: LiveInterpreterController
        setContent {
            val scope = rememberCoroutineScope()
            controller = remember { LiveInterpreterController(FakeClient(), FakeMicrophone(), scope) }
            DisposableEffect(controller) { onDispose { controller.cancel() } }
            TranslationProvider(Translation(BundledTranslations.english)) {
                MaterialTheme { Box(Modifier.size(1_200.dp, 800.dp)) { LiveInterpreterScreen(controller, configuration) } }
            }
        }

        onNodeWithText("Transcription model: Soniox model").assertIsEnabled()
        onNodeWithText("Start").assertIsEnabled()
    }

    private fun configuration(providerModelId: String = "gpt-transcribe", soniox: Boolean = false): AiConfigurationProvider {
        val connection = AiConnection.OpenAiApi(AiConnection.Id("ui-openai"), "OpenAI", enabled = true,
            apiKey = SecretRef.EnvironmentVariable("OPENAI_API_KEY"))
        val model = AiModelConfiguration(AiModelConfiguration.Id("ui-model"), connection.id, providerModelId, "UI model")
        val required = AiRuntimeAssignment.Purpose.entries.filter { it.requiresExplicitAssignment }
        val spec = AiModelSpec(providerModelId, AiProvider.OPENAI,
            required.flatMap { it.requiredCapabilities }.toSet() + AiModelCapability.STRUCTURED_OUTPUT,
            limits = AiModelSpec.Limits(
                textGeneration = AiModelSpec.Limits.TextGeneration(128_000),
                embeddings = AiModelSpec.Limits.Embeddings(dimensions = 2),
            ))
        val sonioxConnection = AiConnection.SonioxApi(AiConnection.Id("ui-soniox"), "Soniox", enabled = true,
            apiKey = SecretRef.EnvironmentVariable("SONIOX_API_KEY"))
        val sonioxModel = AiModelConfiguration(AiModelConfiguration.Id("ui-soniox-model"), sonioxConnection.id,
            "stt-rt-v6", "Soniox model")
        val sonioxSpec = AiModelSpec("stt-rt-v6", AiProvider.SONIOX,
            setOf(AiModelCapability.SPEECH_TO_TEXT, AiModelCapability.REALTIME_TRANSCRIPTION,
                AiModelCapability.SPEAKER_DIARIZATION))
        val assignments = (required + AiRuntimeAssignment.Purpose.LIVE_TRANSLATION).map {
            AiRuntimeAssignment(it, AiRuntimeSelection(
                if (soniox && it == AiRuntimeAssignment.Purpose.LIVE_TRANSCRIPTION) sonioxModel.id else model.id))
        }
        val value = AiCatalogSnapshot(AiCatalog(
            connections = if (soniox) listOf(connection, sonioxConnection) else listOf(connection),
            modelSpecs = if (soniox) listOf(spec, sonioxSpec) else listOf(spec),
            modelConfigurations = if (soniox) listOf(model, sonioxModel) else listOf(model),
            runtimeAssignments = assignments,
            defaultAgentId = AgentDefinition.Id("ui-agent"),
        ), 0)
        return object : AiConfigurationProvider {
            override val snapshotFlow = MutableStateFlow<AiCatalogSnapshot?>(value)
            override val snapshot: AiCatalogSnapshot get() = value
            override fun resolveAiRuntime(selection: AiRuntimeSelection): ResolvedAiRuntime =
                requireNotNull(value.resolveRuntimeIfAvailable(selection))
        }
    }

    private class FakeClient : LiveInterpreterClient {
        val output = Channel<ServerPayload>(Channel.UNLIMITED)
        val requests = mutableListOf<TranslateLiveTextRequest>()
        var stops = 0
        var closes = 0
        override suspend fun start(
            languages: List<String>,
            selection: AiRuntimeSelection,
            nativeTranslationLanguage: String?,
        ): LiveInterpreterRemoteSession {
            output.trySend(LiveInterpreterStatusEvent("ui-session", "interpreter.status.listening"))
            return object : LiveInterpreterRemoteSession {
                override val sessionId = "ui-session"
                override val events = output.receiveAsFlow()
                override suspend fun sendAudioChunk(chunk: RemotePcmAudioChunk) = Unit
                override suspend fun stop() { stops += 1; output.send(LiveInterpreterStoppedEvent(sessionId)); output.close() }
                override suspend fun close() { closes += 1; output.close() }
            }
        }
        override suspend fun translate(request: TranslateLiveTextRequest): LiveTextTranslatedResponse {
            requests += request
            return LiveTextTranslatedResponse(request.revision, request.targetLanguage,
                request.blocks.map { TranslatedLiveBlock(it.id, "${request.targetLanguage}:${it.text}") })
        }
    }
    private class FakeMicrophone : ClientLiveAudioStreamer {
        var stops = 0
        var cancels = 0
        override val unavailableReason: LocalizedText? = null
        override suspend fun start(scope: CoroutineScope, onChunk: suspend (RemotePcmAudioChunk) -> Unit) =
            object : ClientLiveAudioStreamingSession {
                override suspend fun stop() { stops += 1 }
                override fun cancel() { cancels += 1 }
            }
    }
}
