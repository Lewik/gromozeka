package com.gromozeka.presentation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gromozeka.client.RemoteLiveInterpreterService
import com.gromozeka.client.RemoteLiveInterpreterSession
import com.gromozeka.domain.model.Settings
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import com.gromozeka.presentation.services.ClientLiveAudioStreamer
import com.gromozeka.presentation.services.ClientLiveAudioStreamingSession
import com.gromozeka.presentation.services.ClientSideSpeechToTextService
import com.gromozeka.presentation.services.NoOpClientSideSpeechToTextService
import com.gromozeka.remote.protocol.LiveInterpreterDraftsEvent
import com.gromozeka.remote.protocol.LiveInterpreterFailedEvent
import com.gromozeka.remote.protocol.LiveInterpreterStatusEvent
import com.gromozeka.remote.protocol.LiveInterpreterStoppedEvent
import com.gromozeka.remote.protocol.LiveInterpreterTranscriptEvent
import com.gromozeka.remote.protocol.LiveInterpreterTranslationEvent
import com.gromozeka.remote.protocol.RemoteLiveTranscriptChunk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun LiveInterpreterScreen(
    settings: Settings,
    liveInterpreterService: RemoteLiveInterpreterService,
    liveAudioStreamer: ClientLiveAudioStreamer,
    clientSideSpeechToTextService: ClientSideSpeechToTextService = NoOpClientSideSpeechToTextService,
    coroutineScope: CoroutineScope = rememberCoroutineScope(),
    isCompactLayout: Boolean = false,
) {
    val originalItems = remember { mutableStateListOf<LiveInterpreterLine>() }
    val originalDraftItems = remember { mutableStateListOf<LiveInterpreterLine>() }
    val translationItems = remember { mutableStateListOf<LiveInterpreterLine>() }
    val statusItems = remember { mutableStateListOf<String>() }
    var remoteSession by remember { mutableStateOf<RemoteLiveInterpreterSession?>(null) }
    var audioSession by remember { mutableStateOf<ClientLiveAudioStreamingSession?>(null) }
    var eventsJob by remember { mutableStateOf<Job?>(null) }
    var isRunning by remember { mutableStateOf(false) }
    var isStopping by remember { mutableStateOf(false) }
    val clientSideSpeechToTextAvailable = clientSideSpeechToTextService.isEnabled()
    var selectedRecognitionBackend by remember {
        mutableStateOf(
            if (clientSideSpeechToTextAvailable) {
                LiveInterpreterRecognitionBackend.ClientWhisper
            } else {
                LiveInterpreterRecognitionBackend.ServerStt
            }
        )
    }
    val sourceLanguageOptions = remember { liveInterpreterSourceLanguageOptions() }
    var selectedSourceLanguageCode by remember { mutableStateOf(sourceLanguageOptions.first().code) }
    val selectedSourceLanguage = sourceLanguageOptions.firstOrNull { it.code == selectedSourceLanguageCode }
        ?: sourceLanguageOptions.first()
    val translationModelOptions = remember(settings.userProfile.aiSettings.modelConfigurations) {
        settings.userProfile.aiSettings.modelConfigurations.filter {
            it.enabled && (
                AiModelConfiguration.Role.TRANSLATION in it.roles ||
                    AiModelConfiguration.Role.CHAT in it.roles
                )
        }
    }
    var selectedTranslationModelId by remember {
        mutableStateOf(
            translationModelOptions.firstOrNull()?.id
                ?: settings.userProfile.aiSettings.defaultSelection.modelConfigurationId
        )
    }
    val selectedTranslationModel = translationModelOptions.firstOrNull { it.id == selectedTranslationModelId }
        ?: translationModelOptions.firstOrNull()

    LaunchedEffect(selectedTranslationModel?.id) {
        val selected = selectedTranslationModel ?: return@LaunchedEffect
        if (selectedTranslationModelId != selected.id) {
            selectedTranslationModelId = selected.id
        }
    }

    LaunchedEffect(clientSideSpeechToTextAvailable) {
        if (!clientSideSpeechToTextAvailable && selectedRecognitionBackend == LiveInterpreterRecognitionBackend.ClientWhisper) {
            selectedRecognitionBackend = LiveInterpreterRecognitionBackend.ServerStt
        }
    }

    fun finishLocally(message: String) {
        coroutineScope.launch {
            runCatching { audioSession?.stop() }
            remoteSession?.closeLocally()
            eventsJob?.cancel()
            audioSession = null
            remoteSession = null
            eventsJob = null
            isRunning = false
            isStopping = false
            statusItems += message
        }
    }

    fun stop() {
        coroutineScope.launch {
            if (!isRunning && !isStopping) return@launch
            isRunning = false
            isStopping = true
            statusItems += "Stopping: flushing last audio segment..."
            runCatching { audioSession?.stop() }
                .onFailure { statusItems += "Failed to flush audio: ${it.message ?: it::class.simpleName}" }
            audioSession = null
            runCatching { remoteSession?.stop() }
                .onFailure { finishLocally("Failed to stop server session: ${it.message ?: it::class.simpleName}") }
            if (remoteSession == null) {
                finishLocally("Stopped")
            }
        }
    }

    fun start() {
        coroutineScope.launch {
            if (isRunning) return@launch
            originalItems.clear()
            originalDraftItems.clear()
            translationItems.clear()
            statusItems.clear()
            statusItems += "Starting live interpreter..."
            runCatching {
                val session = liveInterpreterService.start(
                    targetLanguage = "ru",
                    sourceLanguageCode = selectedSourceLanguage.code,
                    sourceLanguageHint = selectedSourceLanguage.hint,
                    translationRuntimeSelection = selectedTranslationModel?.let { AiRuntimeSelection(it.id) },
                )
                remoteSession = session
                eventsJob = launch {
                    session.events.collect { event ->
                        when (event) {
                            is LiveInterpreterStatusEvent -> statusItems += event.message
                            is LiveInterpreterTranscriptEvent -> {
                                if (event.isFinal) {
                                    originalItems += LiveInterpreterLine(
                                        segmentId = event.segmentId,
                                        sequenceNumber = event.sequenceNumber,
                                        text = event.text,
                                    )
                                } else {
                                    originalDraftItems += LiveInterpreterLine(
                                        segmentId = event.segmentId,
                                        sequenceNumber = event.sequenceNumber,
                                        text = event.text,
                                    )
                                }
                            }
                            is LiveInterpreterDraftsEvent -> {
                                originalDraftItems.clear()
                                originalDraftItems.addAll(
                                    event.drafts.map { draft ->
                                        LiveInterpreterLine(
                                            segmentId = draft.id,
                                            sequenceNumber = draft.sequenceNumber,
                                            text = draft.text,
                                        )
                                    }
                                )
                            }
                            is LiveInterpreterTranslationEvent -> {
                                translationItems += LiveInterpreterLine(
                                    segmentId = event.segmentId,
                                    sequenceNumber = event.sequenceNumber,
                                    text = event.text,
                                )
                            }
                            is LiveInterpreterStoppedEvent -> finishLocally("Stopped")
                            is LiveInterpreterFailedEvent -> finishLocally("Error: ${event.message}")
                            else -> Unit
                        }
                    }
                }
                val useClientSideSpeechToText =
                    selectedRecognitionBackend == LiveInterpreterRecognitionBackend.ClientWhisper &&
                        clientSideSpeechToTextAvailable
                audioSession = liveAudioStreamer.start(coroutineScope) { chunk ->
                    runCatching {
                        if (useClientSideSpeechToText) {
                            val transcript = clientSideSpeechToTextService.transcribe(
                                chunk = chunk,
                                language = selectedSourceLanguage.code,
                                prompt = selectedSourceLanguage.hint,
                            ).trim()
                            if (transcript.isNotBlank()) {
                                session.sendTranscriptChunk(
                                    RemoteLiveTranscriptChunk(
                                        sequenceNumber = chunk.sequenceNumber,
                                        text = transcript,
                                    )
                                )
                            } else {
                                statusItems += "Segment ${chunk.sequenceNumber}: client speech-to-text returned blank text"
                            }
                        } else {
                            session.sendAudioChunk(chunk)
                        }
                    }.onFailure { error ->
                        statusItems += "Client speech-to-text failed: ${error.message ?: error::class.simpleName}"
                        runCatching { session.stop() }
                        throw error
                    }
                }
                isRunning = true
                isStopping = false
                statusItems += if (useClientSideSpeechToText) {
                    "Listening with client-side Local Whisper"
                } else {
                    "Listening with server speech-to-text"
                }
            }.onFailure { error ->
                runCatching { audioSession?.stop() }
                runCatching { remoteSession?.stop() }
                remoteSession?.closeLocally()
                eventsJob?.cancel()
                audioSession = null
                remoteSession = null
                eventsJob = null
                statusItems += "Failed: ${error.message ?: error::class.simpleName}"
                isRunning = false
                isStopping = false
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            stop()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    "Live interpreter",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    "Original transcript and Russian translation from rolling audio segments.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isRunning || isStopping) {
                OutlinedButton(onClick = ::stop, enabled = isRunning) {
                    Icon(Icons.Default.Stop, contentDescription = null)
                    Text(if (isStopping) "Stopping" else "Stop")
                }
            } else {
                Button(onClick = ::start) {
                    Icon(Icons.Default.Mic, contentDescription = null)
                    Text("Start")
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        LiveInterpreterRecognitionBackendBar(
            selectedBackend = selectedRecognitionBackend,
            clientWhisperAvailable = clientSideSpeechToTextAvailable,
            onBackendChange = { selectedRecognitionBackend = it },
            enabled = !isRunning && !isStopping,
        )

        Spacer(Modifier.height(12.dp))

        LiveInterpreterSourceLanguageBar(
            sourceLanguages = sourceLanguageOptions,
            selectedSourceLanguage = selectedSourceLanguage,
            onSourceLanguageChange = { selectedSourceLanguageCode = it.code },
            enabled = !isRunning && !isStopping,
        )

        Spacer(Modifier.height(12.dp))

        LiveInterpreterModelBar(
            translationModels = translationModelOptions,
            selectedTranslationModel = selectedTranslationModel,
            onTranslationModelChange = { selectedTranslationModelId = it.id },
            enabled = !isRunning && !isStopping,
        )

        Spacer(Modifier.height(12.dp))

        val contentModifier = Modifier.weight(1f)
        if (isCompactLayout) {
            Column(modifier = contentModifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LiveInterpreterColumn("Original", originalItems, Modifier.weight(1f), originalDraftItems)
                LiveInterpreterColumn("Russian", translationItems, Modifier.weight(1f))
            }
        } else {
            Row(modifier = contentModifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LiveInterpreterColumn(
                    "Original",
                    originalItems,
                    Modifier.weight(1f).fillMaxHeight(),
                    originalDraftItems,
                )
                LiveInterpreterColumn("Russian", translationItems, Modifier.weight(1f).fillMaxHeight())
            }
        }

        Spacer(Modifier.height(12.dp))
        LiveInterpreterStatus(statusItems)
    }
}

private enum class LiveInterpreterRecognitionBackend(
    val label: String,
    val description: String,
) {
    ClientWhisper(
        label = "Client Whisper",
        description = "Transcribe locally on this client and send text to the server.",
    ),
    ServerStt(
        label = "Server STT",
        description = "Send audio chunks to the server speech-to-text pipeline.",
    ),
}

private data class LiveInterpreterSourceLanguage(
    val code: String,
    val label: String,
    val hint: String,
)

private fun liveInterpreterSourceLanguageOptions(): List<LiveInterpreterSourceLanguage> =
    listOf(
        LiveInterpreterSourceLanguage(
            "auto",
            "Mixed / auto",
            "The speech may freely switch between Hebrew, English, Russian, and occasional other languages. Keep recognizable foreign terms, names, acronyms, and technical words.",
        ),
        LiveInterpreterSourceLanguage("he", "Hebrew", "Hebrew workplace conversation, possibly with English and Russian terms"),
        LiveInterpreterSourceLanguage("en", "English", "English workplace conversation, possibly with Hebrew and Russian terms"),
        LiveInterpreterSourceLanguage("ru", "Russian", "Russian workplace conversation, possibly with Hebrew and English terms"),
    )

@Composable
private fun LiveInterpreterRecognitionBackendBar(
    selectedBackend: LiveInterpreterRecognitionBackend,
    clientWhisperAvailable: Boolean,
    onBackendChange: (LiveInterpreterRecognitionBackend) -> Unit,
    enabled: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Recognition backend",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
            LiveInterpreterRecognitionBackend.entries.forEachIndexed { index, backend ->
                val available = backend != LiveInterpreterRecognitionBackend.ClientWhisper || clientWhisperAvailable
                val selected = selectedBackend == backend
                Button(
                    onClick = { onBackendChange(backend) },
                    enabled = enabled && available,
                    contentPadding = CompactButtonDefaults.ContentPadding,
                    shape = segmentedButtonShape(index, LiveInterpreterRecognitionBackend.entries.lastIndex),
                    colors = segmentedButtonColors(selected),
                ) {
                    Text(backend.label)
                }
            }
        }
        Text(
            selectedBackend.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LiveInterpreterSourceLanguageBar(
    sourceLanguages: List<LiveInterpreterSourceLanguage>,
    selectedSourceLanguage: LiveInterpreterSourceLanguage,
    onSourceLanguageChange: (LiveInterpreterSourceLanguage) -> Unit,
    enabled: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Spoken language",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
            sourceLanguages.forEachIndexed { index, sourceLanguage ->
                val selected = selectedSourceLanguage.code == sourceLanguage.code
                Button(
                    onClick = { onSourceLanguageChange(sourceLanguage) },
                    enabled = enabled,
                    contentPadding = CompactButtonDefaults.ContentPadding,
                    shape = segmentedButtonShape(index, sourceLanguages.lastIndex),
                    colors = segmentedButtonColors(selected),
                ) {
                    Text(sourceLanguage.label)
                }
            }
        }
    }
}

@Composable
private fun LiveInterpreterModelBar(
    translationModels: List<AiModelConfiguration>,
    selectedTranslationModel: AiModelConfiguration?,
    onTranslationModelChange: (AiModelConfiguration) -> Unit,
    enabled: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Translation model",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (translationModels.isEmpty()) {
            Text(
                "No translation model is configured; default chat model will be used.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                translationModels.forEachIndexed { index, model ->
                    val selected = selectedTranslationModel?.id == model.id
                    Button(
                        onClick = { onTranslationModelChange(model) },
                        enabled = enabled,
                        contentPadding = CompactButtonDefaults.ContentPadding,
                        shape = segmentedButtonShape(index, translationModels.lastIndex),
                        colors = segmentedButtonColors(selected),
                    ) {
                        Text(model.displayName)
                    }
                }
            }
        }
    }
}

@Composable
private fun segmentedButtonColors(selected: Boolean) =
    if (selected) {
        ButtonDefaults.buttonColors()
    } else {
        ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.36f),
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.36f),
        )
    }

private fun segmentedButtonShape(index: Int, lastIndex: Int) =
    when (index) {
        0 -> if (lastIndex == 0) {
            androidx.compose.foundation.shape.RoundedCornerShape(CompactButtonDefaults.CornerRadius)
        } else {
            androidx.compose.foundation.shape.RoundedCornerShape(
                topStart = CompactButtonDefaults.CornerRadius,
                bottomStart = CompactButtonDefaults.CornerRadius,
                topEnd = 0.dp,
                bottomEnd = 0.dp,
            )
        }
        lastIndex -> androidx.compose.foundation.shape.RoundedCornerShape(
            topStart = 0.dp,
            bottomStart = 0.dp,
            topEnd = CompactButtonDefaults.CornerRadius,
            bottomEnd = CompactButtonDefaults.CornerRadius,
        )
        else -> androidx.compose.foundation.shape.RoundedCornerShape(0.dp)
    }

@Composable
private fun LiveInterpreterColumn(
    title: String,
    lines: List<LiveInterpreterLine>,
    modifier: Modifier,
    draftLines: List<LiveInterpreterLine> = emptyList(),
) {
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size, draftLines.size) {
        val itemCount = lines.size + draftLines.size + if (draftLines.isNotEmpty()) 1 else 0
        if (itemCount > 0) {
            listState.animateScrollToItem(itemCount - 1)
        }
    }

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(8.dp))
            if (lines.isEmpty() && draftLines.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No text yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(lines, key = { "${it.segmentId}:${it.sequenceNumber}" }) { line ->
                        Text(
                            text = line.text,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    if (draftLines.isNotEmpty()) {
                        item(key = "draft-label") {
                            Text(
                                text = "Pending drafts",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        items(draftLines, key = { "draft:${it.segmentId}:${it.sequenceNumber}" }) { line ->
                            Text(
                                text = line.text,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveInterpreterStatus(items: List<String>) {
    val last = items.lastOrNull() ?: "Idle"
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f),
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Text(
            text = last,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private data class LiveInterpreterLine(
    val segmentId: String,
    val sequenceNumber: Int,
    val text: String,
)
