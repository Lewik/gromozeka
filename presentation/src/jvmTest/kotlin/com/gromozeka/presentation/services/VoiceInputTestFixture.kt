package com.gromozeka.presentation.services

import com.gromozeka.client.AudioTranscriptionService
import com.gromozeka.domain.model.*
import com.gromozeka.domain.service.SettingsService
import com.gromozeka.presentation.services.translation.data.Translation
import com.gromozeka.presentation.ui.viewmodel.AppViewModel
import com.gromozeka.remote.protocol.RemoteAudioRecording
import com.gromozeka.shared.audio.SpeechPcmWav
import java.lang.reflect.Proxy
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

internal class VoiceInputTestFixture(scope: CoroutineScope, autoSend: Boolean) {
    val settings = VoiceTestSettings(Settings(
        userProfile = UserProfile().let { it.copy(speechSettings = it.speechSettings.copy(
            speechToText = it.speechSettings.speechToText.copy(enabled = true),
        )) },
        userDeviceSettings = UserDeviceSettings.Desktop(
            voiceInputSettings = UserDeviceSettings.VoiceInputSettings(autoSend = autoSend),
        ),
    ))
    val submitted = mutableListOf<Conversation.Message>()
    val recorder = VoiceTestRecorder()
    val transcription = VoiceTestTranscription()
    val app = AppViewModel(
        currentUserAuthor = Conversation.Message.Author.User(User.Id("user"), "User"),
        agentService = voiceStub { name, _ ->
            when {
                name.startsWith("observeByProject") -> emptyFlow<Nothing>()
                name.startsWith("findByProject") -> emptyList<AgentDefinition>()
                else -> error(name)
            }
        },
        conversationRuntimeService = voiceStub { name, args ->
            when {
                name.startsWith("observe") -> emptyFlow<Nothing>()
                name.startsWith("postMessage") -> { submitted += args.filterIsInstance<Conversation.Message>().single(); true }
                else -> error(name)
            }
        },
        conversationService = voiceStub { name, args ->
            when {
                name.startsWith("findById") -> conversation(args.first().toString())
                name.startsWith("observeByProject") -> emptyFlow<Nothing>()
                name.startsWith("loadCurrentMessages") -> emptyList<Conversation.Message>()
                else -> error(name)
            }
        },
        conversationHistoryService = voiceStub(),
        settingsService = settings,
        scope = scope,
        attachmentAcquisitionController = NoOpAttachmentAcquisitionController,
        artifactTransferService = voiceStub(),
        defaultAgentProvider = voiceStub(),
        tokenStatsService = voiceStub { name, _ -> if (name.startsWith("getTokenStats")) null else error(name) },
        conversationTabLayoutService = voiceStub { name, _ ->
            when {
                name.startsWith("open") || name.startsWith("close") -> ConversationTabLayout()
                else -> error(name)
            }
        },
        conversationUnreadStateService = voiceStub { name, _ ->
            if (name.startsWith("observe")) emptyFlow<Nothing>() else error(name)
        },
        messageInputClientPlatform = MessageInputContext.ClientPlatform.DESKTOP,
        turnCompletionNotificationService = TurnCompletionNotificationService(settings, NoOpTurnCompletionNotificationSink),
        currentTranslation = { Translation.builtIn.getValue("en") },
    )

    suspend fun open(id: String) = app.createTab(
        Project.Id("project"), null, Conversation.Id(id), null, true, ConversationInitiator.User,
    )

    private fun conversation(id: String) = Conversation(
        id = Conversation.Id(id), projectId = Project.Id("project"),
        participants = setOf(Conversation.Participant.User(User.Id("user"))),
        currentThread = Conversation.Thread.Id("thread-$id"),
        createdAt = Instant.fromEpochSeconds(0), updatedAt = Instant.fromEpochSeconds(0),
    )
}

internal class VoiceTestSettings(initial: Settings) : SettingsService {
    private val mutableSettings = MutableStateFlow(initial)
    override val settingsFlow: StateFlow<Settings> = mutableSettings
    override val settings get() = mutableSettings.value
    override val userProfile get() = settings.userProfile
    override val userDeviceSettings get() = settings.userDeviceSettings
    override val mode = AppMode.PRODUCTION
    override val homeDirectory = "/tmp/gromozeka-voice-test"
    override fun saveSettings(settings: Settings) { mutableSettings.value = settings }
    override fun saveSettings(block: Settings.() -> Settings) { saveSettings(settings.block()) }
    override fun reloadSettings() = Unit
}

internal class VoiceTestRecorder : ClientAudioRecorder {
    var preparation = CompletableDeferred(Unit)
    val chunks = MutableSharedFlow<ByteArray>(extraBufferCapacity = 32)
    var cancellations = 0
    override val supportsStreamingAudioChunks = true
    override suspend fun start(scope: CoroutineScope): ClientAudioRecordingSession {
        preparation.await()
        return object : ClientAudioRecordingSession {
            override val audioChunks = chunks
            override suspend fun stop() = ClientRecordedAudio(SpeechPcmWav.encode(ByteArray(640)), SpeechAudioFormat.WAV_PCM_S16LE_MONO_16_KHZ)
            override fun cancel() { cancellations++ }
        }
    }
}

internal class VoiceTestTranscription : AudioTranscriptionService {
    val requests = Channel<CompletableDeferred<String>>(Channel.UNLIMITED)
    var preparation = CompletableDeferred(Unit)
    override suspend fun transcribe(recording: RemoteAudioRecording) = recognize()
    override suspend fun captureUnavailableReason(): SpeechAvailabilityFailure? = null
    override suspend fun startCapture(sessionId: String) { preparation.await() }
    override suspend fun stopCapture(sessionId: String) = recognize()
    override suspend fun cancelCapture(sessionId: String) = Unit
    private suspend fun recognize(): String {
        val result = CompletableDeferred<String>()
        requests.send(result)
        return result.await()
    }
}

@Suppress("UNCHECKED_CAST")
internal inline fun <reified T> voiceStub(noinline call: (String, Array<out Any?>) -> Any? = { name, _ -> error(name) }): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
        call(method.name, args.orEmpty())
    } as T
