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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import com.gromozeka.presentation.ui.icons.Icon
import com.gromozeka.presentation.ui.icons.Icons
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import com.gromozeka.domain.model.ai.AiRuntimeAssignment
import com.gromozeka.domain.service.AiConfigurationProvider
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import com.gromozeka.presentation.services.translation.LocalizedText
import com.gromozeka.presentation.services.translation.localizedText
import com.gromozeka.presentation.services.translation.data.Translation
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
    aiConfigurationProvider: AiConfigurationProvider,
    liveInterpreterService: RemoteLiveInterpreterService,
    liveAudioStreamer: ClientLiveAudioStreamer,
    clientSideSpeechToTextService: ClientSideSpeechToTextService = NoOpClientSideSpeechToTextService,
    coroutineScope: CoroutineScope = rememberCoroutineScope(),
    isCompactLayout: Boolean = false,
) {
    val translation = LocalTranslation.current
    var sessionTargetLanguageName by remember { mutableStateOf<String?>(null) }
    val originalItems = remember { mutableStateListOf<LiveInterpreterLine>() }
    val originalDraftItems = remember { mutableStateListOf<LiveInterpreterLine>() }
    val translationItems = remember { mutableStateListOf<LiveInterpreterLine>() }
    val statusItems = remember { mutableStateListOf<LocalizedText>() }
    var remoteSession by remember { mutableStateOf<RemoteLiveInterpreterSession?>(null) }
    var audioSession by remember { mutableStateOf<ClientLiveAudioStreamingSession?>(null) }
    var eventsJob by remember { mutableStateOf<Job?>(null) }
    var isRunning by remember { mutableStateOf(false) }
    var isStopping by remember { mutableStateOf(false) }
    val clientSideSpeechToTextAvailable = clientSideSpeechToTextService.isAvailable()
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
    val aiSnapshot by aiConfigurationProvider.snapshotFlow.collectAsState()
    val translationModelOptions = remember(aiSnapshot) {
        aiSnapshot?.catalog?.modelConfigurations.orEmpty().filter {
            aiSnapshot?.supportsPurpose(it, AiRuntimeAssignment.Purpose.LIVE_TRANSLATION) == true
        }
    }
    var selectedTranslationModelId by remember {
        mutableStateOf(
            aiConfigurationProvider.catalog.runtimeSelectionFor(
                AiRuntimeAssignment.Purpose.LIVE_TRANSLATION
            )?.modelConfigurationId
                ?: translationModelOptions.firstOrNull()?.id
                ?: aiConfigurationProvider.catalog.runtimeSelectionFor(
                    AiRuntimeAssignment.Purpose.DEFAULT_CHAT
                )?.modelConfigurationId
                ?: aiConfigurationProvider.catalog.modelConfigurations.first().id
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

    fun finishLocally(message: LocalizedText) {
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
            statusItems += localizedText("interpreter.flushingAudio")
            runCatching { audioSession?.stop() }
                .onFailure { statusItems += localizedText("interpreter.flushFailed", "error" to it.localizedText()) }
            audioSession = null
            runCatching { remoteSession?.stop() }
                .onFailure { finishLocally(localizedText("interpreter.stopFailed", "error" to it.localizedText())) }
            if (remoteSession == null) {
                finishLocally(localizedText("interpreter.stopped"))
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
            sessionTargetLanguageName = translation.languageName
            statusItems += localizedText("interpreter.starting")
            runCatching {
                val session = liveInterpreterService.start(
                    targetLanguage = translation.languageCode,
                    sourceLanguageCode = selectedSourceLanguage.code,
                    sourceLanguageHint = selectedSourceLanguage.hint,
                    translationRuntimeSelection = selectedTranslationModel?.let { AiRuntimeSelection(it.id) },
                )
                remoteSession = session
                eventsJob = launch {
                    session.events.collect { event ->
                        when (event) {
                            is LiveInterpreterStatusEvent -> statusItems += LocalizedText.Resource(
                                event.messageKey,
                                event.arguments.mapValues { (_, value) -> LocalizedText.Literal(value) },
                            )
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
                            is LiveInterpreterStoppedEvent -> finishLocally(localizedText("interpreter.stopped"))
                            is LiveInterpreterFailedEvent -> finishLocally(localizedText("interpreter.failed", "error" to event.message))
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
                                language = selectedSourceLanguage.code.substringBefore('-'),
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
                                statusItems += localizedText("interpreter.emptySegment", "sequence" to chunk.sequenceNumber)
                            }
                        } else {
                            session.sendAudioChunk(chunk)
                        }
                    }.onFailure { error ->
                        statusItems += localizedText("interpreter.transcriptionFailed", "error" to error.localizedText())
                        runCatching { session.stop() }
                        throw error
                    }
                }
                isRunning = true
                isStopping = false
                statusItems += if (useClientSideSpeechToText) {
                    localizedText("interpreter.listeningClient")
                } else {
                    localizedText("interpreter.listeningServer")
                }
            }.onFailure { error ->
                runCatching { audioSession?.stop() }
                runCatching { remoteSession?.stop() }
                remoteSession?.closeLocally()
                eventsJob?.cancel()
                audioSession = null
                remoteSession = null
                eventsJob = null
                statusItems += localizedText("interpreter.failed", "error" to error.localizedText())
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
                    translation.text("interpreter.title"),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    translation.text("interpreter.description"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isRunning || isStopping) {
                OutlinedButton(onClick = ::stop, enabled = isRunning) {
                    Icon(Icons.Default.Stop, contentDescription = null)
                    Text(if (isStopping) translation.text("interpreter.stopping") else translation.text("interpreter.stop"))
                }
            } else {
                Button(onClick = ::start) {
                    Icon(Icons.Default.Mic, contentDescription = null)
                    Text(translation.text("interpreter.start"))
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(translation.text("interpreter.targetLanguage"), style = MaterialTheme.typography.labelMedium)
            Text(
                if (isRunning || isStopping) sessionTargetLanguageName ?: translation.languageName else translation.languageName,
                style = MaterialTheme.typography.labelMedium,
            )
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
                LiveInterpreterColumn(translation.text("interpreter.original"), originalItems, Modifier.weight(1f), originalDraftItems)
                LiveInterpreterColumn(sessionTargetLanguageName ?: translation.languageName, translationItems, Modifier.weight(1f))
            }
        } else {
            Row(modifier = contentModifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LiveInterpreterColumn(
                    translation.text("interpreter.original"),
                    originalItems,
                    Modifier.weight(1f).fillMaxHeight(),
                    originalDraftItems,
                )
                LiveInterpreterColumn(sessionTargetLanguageName ?: translation.languageName, translationItems, Modifier.weight(1f).fillMaxHeight())
            }
        }

        Spacer(Modifier.height(12.dp))
        LiveInterpreterStatus(statusItems)
    }
}

private enum class LiveInterpreterRecognitionBackend(
    val labelKey: String,
    val descriptionKey: String,
) {
    ClientWhisper("interpreter.clientWhisper", "interpreter.clientWhisperDescription"),
    ServerStt("interpreter.serverStt", "interpreter.serverSttDescription"),
}

private data class LiveInterpreterSourceLanguage(
    val code: String,
    val label: LocalizedText,
    val hint: String,
)

private fun liveInterpreterSourceLanguageOptions(): List<LiveInterpreterSourceLanguage> =
    listOf(
        LiveInterpreterSourceLanguage(
            "auto",
            localizedText("interpreter.autoLanguage"),
            "Speech may freely switch between languages. Preserve recognizable foreign terms, names, acronyms, and technical words.",
        )
    ) + Translation.builtIn.values.map { language ->
        LiveInterpreterSourceLanguage(
            language.languageCode,
            LocalizedText.Literal(language.languageName),
            "Speech primarily in ${language.languageName}, possibly including words and phrases from other languages. Preserve names and technical terms.",
        )
    }

@Composable
private fun LiveInterpreterRecognitionBackendBar(
    selectedBackend: LiveInterpreterRecognitionBackend,
    clientWhisperAvailable: Boolean,
    onBackendChange: (LiveInterpreterRecognitionBackend) -> Unit,
    enabled: Boolean,
) {
    val translation = LocalTranslation.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            translation.text("interpreter.recognitionBackend"),
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
                    Text(translation.text(backend.labelKey))
                }
            }
        }
        Text(
            translation.text(selectedBackend.descriptionKey),
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
    val translation = LocalTranslation.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            translation.text("interpreter.sourceLanguage"),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
            itemsIndexed(sourceLanguages) { index, sourceLanguage ->
                val selected = selectedSourceLanguage.code == sourceLanguage.code
                Button(
                    onClick = { onSourceLanguageChange(sourceLanguage) },
                    enabled = enabled,
                    contentPadding = CompactButtonDefaults.ContentPadding,
                    shape = segmentedButtonShape(index, sourceLanguages.lastIndex),
                    colors = segmentedButtonColors(selected),
                ) {
                    Text(sourceLanguage.label.resolve(translation))
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
    val translation = LocalTranslation.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            translation.text("interpreter.translationModel"),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (translationModels.isEmpty()) {
            Text(
                translation.text("interpreter.noTranslationModel"),
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
    val translation = LocalTranslation.current
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
                    Text(translation.text("interpreter.noText"), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                text = translation.text("interpreter.pendingDrafts"),
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
private fun LiveInterpreterStatus(items: List<LocalizedText>) {
    val translation = LocalTranslation.current
    val last = items.lastOrNull() ?: localizedText("interpreter.idle")
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f),
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Text(
            text = last.resolve(translation),
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
