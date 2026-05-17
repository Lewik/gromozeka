package com.gromozeka.server

import com.gromozeka.application.service.AiConversationMessageMapper
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiRuntimeOptions
import com.gromozeka.domain.model.ai.AiRuntimeRequest
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import com.gromozeka.domain.model.ai.AiToolChoice
import com.gromozeka.domain.service.AiRuntimeProvider
import com.gromozeka.domain.service.SettingsService
import com.gromozeka.infrastructure.ai.openai.SttService
import com.gromozeka.remote.protocol.LiveInterpreterAudioChunkCommand
import com.gromozeka.remote.protocol.LiveInterpreterDraftsEvent
import com.gromozeka.remote.protocol.LiveInterpreterFailedEvent
import com.gromozeka.remote.protocol.LiveInterpreterStartedResponse
import com.gromozeka.remote.protocol.LiveInterpreterStatusEvent
import com.gromozeka.remote.protocol.LiveInterpreterStoppedEvent
import com.gromozeka.remote.protocol.LiveInterpreterTranscriptEvent
import com.gromozeka.remote.protocol.LiveInterpreterTranslationEvent
import com.gromozeka.remote.protocol.RemoteLiveAudioChunk
import com.gromozeka.remote.protocol.RemoteLiveInterpreterDraft
import com.gromozeka.remote.protocol.ServerPayload
import com.gromozeka.remote.protocol.StartLiveInterpreterRequest
import com.gromozeka.remote.protocol.StopLiveInterpreterCommand
import com.gromozeka.shared.uuid.uuid7
import klog.KLoggers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

@Service
class LiveInterpreterApplicationService(
    private val sttService: SttService,
    private val aiRuntimeProvider: AiRuntimeProvider,
    private val settingsService: SettingsService,
    @param:Qualifier("supervisorScope") private val scope: CoroutineScope,
) {
    private val log = KLoggers.logger(this)
    private val sessions = ConcurrentHashMap<String, LiveInterpreterSession>()

    fun start(
        request: StartLiveInterpreterRequest,
        eventSink: suspend (ServerPayload) -> Unit,
    ): LiveInterpreterStartedResponse {
        val sessionId = uuid7()
        val session = LiveInterpreterSession(
            sessionId = sessionId,
            targetLanguage = request.targetLanguage.ifBlank { "ru" },
            sourceLanguageCode = request.sourceLanguageCode.ifBlank { "auto" },
            sourceLanguageHint = request.sourceLanguageHint.ifBlank {
                "Hebrew, Russian, and English workplace conversation"
            },
            translationRuntimeSelection = request.translationRuntimeSelection ?: selectTranslationRuntime(),
            eventSink = eventSink,
        )
        sessions[sessionId] = session
        session.start()
        log.info {
            "Live interpreter started: session=$sessionId target=${session.targetLanguage} " +
                "sourceLanguage=${session.sourceLanguageCode} " +
                "translationRuntime=${session.translationRuntimeSelection.modelConfigurationId.value}"
        }
        return LiveInterpreterStartedResponse(sessionId)
    }

    suspend fun append(command: LiveInterpreterAudioChunkCommand) {
        val session = sessions[command.sessionId]
        if (session == null) {
            log.warn { "Live interpreter chunk ignored for missing session=${command.sessionId}" }
            return
        }
        session.append(command.chunk)
    }

    suspend fun stop(command: StopLiveInterpreterCommand) {
        val session = sessions.remove(command.sessionId) ?: return
        session.stop()
    }

    private inner class LiveInterpreterSession(
        val sessionId: String,
        val targetLanguage: String,
        val sourceLanguageCode: String,
        val sourceLanguageHint: String,
        val translationRuntimeSelection: AiRuntimeSelection,
        private val eventSink: suspend (ServerPayload) -> Unit,
    ) {
        private val chunks = Channel<RemoteLiveAudioChunk>(Channel.UNLIMITED)
        private val stabilizationSignals = Channel<Unit>(Channel.CONFLATED)
        private val finalizedOriginalQueue = Channel<LiveInterpreterFinalizedOriginal>(Channel.UNLIMITED)
        private val transcriptState = LiveInterpreterTranscriptState()
        private val transcriptStateMutex = Mutex()
        private val emitMutex = Mutex()
        private var nextFinalSequenceNumber = 0

        fun start() {
            scope.launch {
                emit(LiveInterpreterStatusEvent(sessionId, "Live interpreter is listening"))
                runCatching {
                    coroutineScope {
                        val transcriptionJob = launch { runTranscriptionLoop() }
                        val stabilizationJob = launch { runStabilizationLoop() }
                        val translationJob = launch { runTranslationLoop() }

                        transcriptionJob.invokeOnCompletion { stabilizationSignals.close() }
                        stabilizationJob.invokeOnCompletion { finalizedOriginalQueue.close() }

                        joinAll(transcriptionJob, stabilizationJob, translationJob)
                    }
                }.onFailure { error ->
                    log.warn(error) { "Live interpreter failed: session=$sessionId error=${error.message}" }
                    emit(LiveInterpreterFailedEvent(sessionId, error.message ?: "Live interpreter failed"))
                }.also {
                    sessions.remove(sessionId, this@LiveInterpreterSession)
                    emit(LiveInterpreterStoppedEvent(sessionId))
                }
            }
        }

        suspend fun append(chunk: RemoteLiveAudioChunk) {
            chunks.send(chunk)
        }

        suspend fun stop() {
            chunks.close()
        }

        private suspend fun runTranscriptionLoop() {
            for (chunk in chunks) {
                if (!coroutineContext.isActive) break
                transcribeChunk(chunk)
            }
        }

        private suspend fun transcribeChunk(chunk: RemoteLiveAudioChunk) {
            log.info {
                "Live interpreter chunk received: session=$sessionId chunk=${chunk.sequenceNumber} " +
                    "bytes=${chunk.data.size} mediaType=${chunk.mediaType}"
            }
            emit(LiveInterpreterStatusEvent(sessionId, "Transcribing segment ${chunk.sequenceNumber}"))
            val transcript = sttService.transcribe(
                audioData = chunk.data,
                fileExtension = chunk.fileExtension,
                mediaType = chunk.mediaType,
                language = sourceLanguageCode,
                prompt = sourceLanguageHint,
            ).trim()
            if (transcript.isBlank()) {
                emit(LiveInterpreterStatusEvent(sessionId, "Segment ${chunk.sequenceNumber}: no speech detected"))
                return
            }

            val draft = transcriptStateMutex.withLock {
                transcriptState.recordDraft(chunk.sequenceNumber, transcript)
            }
            log.info {
                "Live interpreter draft transcript: session=$sessionId draft=${draft.id} chars=${transcript.length} " +
                    "text='${transcript.liveLogSnippet()}'"
            }
            emit(
                LiveInterpreterTranscriptEvent(
                    sessionId = sessionId,
                    segmentId = draft.id,
                    sequenceNumber = draft.sequenceNumber,
                    text = transcript,
                    isFinal = false,
                )
            )
            emitPendingDrafts()
            stabilizationSignals.trySend(Unit)
        }

        private suspend fun runStabilizationLoop() {
            for (ignored in stabilizationSignals) {
                if (!coroutineContext.isActive) break
                stabilizePendingDrafts()
            }
            stabilizePendingDrafts()
        }

        private suspend fun stabilizePendingDrafts() {
            val context = transcriptStateMutex.withLock {
                transcriptState.stabilizerContext()
            }
            if (context.pendingDrafts.isEmpty()) {
                return
            }
            val newestDraft = context.pendingDrafts.maxOf { it.sequenceNumber }
            emit(LiveInterpreterStatusEvent(sessionId, "Stabilizing transcript draft $newestDraft"))
            val finalizedOriginalSegments = runCatching {
                val response = stabilizeTranscript(context)
                log.info {
                    "Live interpreter stabilizer response: session=$sessionId pending=${context.pendingDrafts.size} " +
                        "append=${response.appendFinalOriginal.size} keep=${response.keepDraftIds} " +
                        "drop=${response.dropDraftIds} appendText='${response.appendFinalOriginal.joinToString(" | ").liveLogSnippet()}'"
                }
                transcriptStateMutex.withLock {
                    transcriptState.applyStabilizerResponse(response)
                }
            }.onFailure { error ->
                log.warn(error) {
                    "Live interpreter transcript stabilization failed: session=$sessionId " +
                        "draft=$newestDraft error=${error.message}"
                }
                emit(
                    LiveInterpreterStatusEvent(
                        sessionId = sessionId,
                        message = "Transcript stabilization failed for draft $newestDraft: ${error.message}"
                    )
                )
            }.getOrNull().orEmpty()
            emitPendingDrafts()

            if (finalizedOriginalSegments.isEmpty()) {
                emit(LiveInterpreterStatusEvent(sessionId, "Draft $newestDraft: waiting for more speech"))
                return
            }

            val finalSequenceNumber = nextFinalSequenceNumber++
            val finalSegmentId = "final-$finalSequenceNumber"
            val finalizedOriginalText = finalizedOriginalSegments.joinToString("\n")
            log.info {
                "Live interpreter finalized transcript: session=$sessionId sequence=$finalSequenceNumber " +
                    "segments=${finalizedOriginalSegments.size} chars=${finalizedOriginalText.length} " +
                    "text='${finalizedOriginalText.liveLogSnippet()}'"
            }
            emit(
                LiveInterpreterTranscriptEvent(
                    sessionId = sessionId,
                    segmentId = finalSegmentId,
                    sequenceNumber = finalSequenceNumber,
                    text = finalizedOriginalText,
                    isFinal = true,
                )
            )
            finalizedOriginalQueue.send(
                LiveInterpreterFinalizedOriginal(
                    segmentId = finalSegmentId,
                    sequenceNumber = finalSequenceNumber,
                    segments = finalizedOriginalSegments,
                )
            )
        }

        private suspend fun runTranslationLoop() {
            for (finalizedOriginal in finalizedOriginalQueue) {
                if (!coroutineContext.isActive) break
                translateFinalizedOriginal(finalizedOriginal)
            }
        }

        private suspend fun translateFinalizedOriginal(finalizedOriginal: LiveInterpreterFinalizedOriginal) {
            emit(LiveInterpreterStatusEvent(sessionId, "Translating finalized transcript ${finalizedOriginal.sequenceNumber}"))
            runCatching {
                val context = transcriptStateMutex.withLock {
                    transcriptState.translationContext(finalizedOriginal.segments)
                }
                translate(context)
            }
                .onFailure { error ->
                    log.warn(error) {
                        "Live interpreter translation failed: session=$sessionId " +
                            "sequence=${finalizedOriginal.sequenceNumber} error=${error.message}"
                    }
                    emit(
                        LiveInterpreterStatusEvent(
                            sessionId = sessionId,
                            message = "Translation failed for finalized transcript ${finalizedOriginal.sequenceNumber}: ${error.message}"
                        )
                    )
                }
                .getOrNull()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { translation ->
                    transcriptStateMutex.withLock {
                        transcriptState.recordTranslationSegment(translation)
                    }
                    log.info {
                        "Live interpreter translated transcript: session=$sessionId " +
                            "sequence=${finalizedOriginal.sequenceNumber} chars=${translation.length} " +
                            "text='${translation.liveLogSnippet()}'"
                    }
                    emit(
                        LiveInterpreterTranslationEvent(
                            sessionId = sessionId,
                            segmentId = finalizedOriginal.segmentId,
                            sequenceNumber = finalizedOriginal.sequenceNumber,
                            text = translation,
                            targetLanguage = targetLanguage,
                            isFinal = true,
                        )
                    )
                }
        }

        private suspend fun stabilizeTranscript(context: LiveInterpreterStabilizerPromptContext): TranscriptStabilizerResponse {
            val text = callTextModel(
                systemPrompt = buildLiveInterpreterStabilizerSystemPrompt(sourceLanguageHint),
                userPrompt = buildLiveInterpreterStabilizerUserPrompt(context),
                maxOutputTokens = 900,
            )
            return parseTranscriptStabilizerXml(text)
        }

        private suspend fun translate(context: LiveInterpreterTranslationPromptContext): String {
            return callTextModel(
                systemPrompt = buildLiveInterpreterTranslationSystemPrompt(
                    targetLanguage = targetLanguage,
                    sourceLanguageHint = sourceLanguageHint,
                ),
                userPrompt = buildLiveInterpreterTranslationUserPrompt(context),
                maxOutputTokens = 800,
            )
        }

        private suspend fun callTextModel(
            systemPrompt: String,
            userPrompt: String,
            maxOutputTokens: Int,
        ): String {
            val runtime = aiRuntimeProvider.getRuntime(translationRuntimeSelection, null)
            val response = runtime.call(
                AiRuntimeRequest(
                    systemPrompts = listOf(systemPrompt),
                    messages = listOf(
                        Conversation.Message(
                            id = Conversation.Message.Id(uuid7()),
                            conversationId = Conversation.Id("live-interpreter:$sessionId"),
                            role = Conversation.Message.Role.USER,
                            content = listOf(
                                Conversation.Message.ContentItem.UserMessage(
                                    userPrompt
                                )
                            ),
                            createdAt = Clock.System.now(),
                        )
                    ),
                    options = AiRuntimeOptions(
                        maxOutputTokens = maxOutputTokens,
                        toolChoice = AiToolChoice.None,
                        assistantResponseFormat = AiModelConfiguration.AssistantResponseFormat.TEXT,
                    ),
                )
            )
            return AiConversationMessageMapper.extractAssistantText(response)
        }

        private suspend fun emitPendingDrafts() {
            val drafts = transcriptStateMutex.withLock {
                transcriptState.pendingDrafts()
            }
            emit(
                LiveInterpreterDraftsEvent(
                    sessionId = sessionId,
                    drafts = drafts.map { draft ->
                        RemoteLiveInterpreterDraft(
                            id = draft.id,
                            sequenceNumber = draft.sequenceNumber,
                            text = draft.text,
                        )
                    },
                )
            )
        }

        private suspend fun emit(payload: ServerPayload) {
            runCatching {
                emitMutex.withLock {
                    eventSink(payload)
                }
            }
                .onFailure { error ->
                    log.warn(error) { "Live interpreter event send failed: session=$sessionId error=${error.message}" }
                }
        }
    }

    private fun selectTranslationRuntime(): AiRuntimeSelection {
        val settings = settingsService.settings
        val translationModel = settings.userProfile.aiSettings.modelConfigurations.firstOrNull {
            it.enabled && AiModelConfiguration.Role.TRANSLATION in it.roles
        } ?: settings.userProfile.aiSettings.modelConfigurations.firstOrNull {
            it.enabled && AiModelConfiguration.Role.CHAT in it.roles
        }
        return AiRuntimeSelection(translationModel?.id ?: settings.userProfile.aiSettings.defaultSelection.modelConfigurationId)
    }
}

private fun String.toTargetLanguageName(): String =
    when (lowercase()) {
        "ru", "rus", "russian" -> "Russian"
        "en", "eng", "english" -> "English"
        "he", "heb", "hebrew" -> "Hebrew"
        else -> this
    }

private fun String.liveLogSnippet(maxLength: Int = 240): String {
    val compact = replace(Regex("\\s+"), " ").trim()
    return if (compact.length <= maxLength) compact else compact.take(maxLength) + "..."
}

internal class LiveInterpreterTranscriptState {
    private val finalizedOriginalSegments = mutableListOf<String>()
    private val finalizedTranslationSegments = mutableListOf<String>()
    private val pendingDrafts = mutableListOf<LiveInterpreterDraftChunk>()

    fun recordDraft(sequenceNumber: Int, text: String): LiveInterpreterDraftChunk {
        val draft = LiveInterpreterDraftChunk(
            id = "draft-$sequenceNumber",
            sequenceNumber = sequenceNumber,
            text = text,
        )
        pendingDrafts.removeAll { it.id == draft.id }
        pendingDrafts += draft
        trimPendingDrafts()
        return draft
    }

    fun stabilizerContext(): LiveInterpreterStabilizerPromptContext =
        LiveInterpreterStabilizerPromptContext(
            finalizedOriginalTranscriptTail = finalizedOriginalSegments.takeLast(MAX_FINAL_SEGMENTS_IN_PROMPT)
                .joinToString("\n"),
            pendingDrafts = pendingDrafts.toList(),
        )

    fun pendingDrafts(): List<LiveInterpreterDraftChunk> = pendingDrafts.toList()

    fun applyStabilizerResponse(response: TranscriptStabilizerResponse): List<String> {
        val finalized = response.appendFinalOriginal.map { it.trim() }.filter { it.isNotBlank() }
        val keepIds = response.keepDraftIds.toSet()
        val dropIds = response.dropDraftIds.toSet()

        if (finalized.isNotEmpty()) {
            finalizedOriginalSegments += finalized
            compactFinalizedOriginal()
        }

        pendingDrafts.removeAll { draft -> draft.id in dropIds && draft.id !in keepIds }
        trimPendingDrafts()
        return finalized
    }

    fun recordTranslationSegment(text: String) {
        finalizedTranslationSegments += text
        if (finalizedTranslationSegments.size > MAX_FINAL_SEGMENTS_RETAINED) {
            repeat(FINAL_SEGMENTS_TRIM_COUNT) {
                if (finalizedTranslationSegments.isNotEmpty()) {
                    finalizedTranslationSegments.removeAt(0)
                }
            }
        }
    }

    fun translationContext(finalizedOriginalDelta: List<String>): LiveInterpreterTranslationPromptContext =
        LiveInterpreterTranslationPromptContext(
            finalizedOriginalTranscriptTail = finalizedOriginalSegments.takeLast(MAX_FINAL_SEGMENTS_IN_PROMPT)
                .joinToString("\n"),
            publishedTranslationTail = finalizedTranslationSegments.takeLast(MAX_FINAL_SEGMENTS_IN_PROMPT)
                .joinToString("\n"),
            newFinalOriginalDelta = finalizedOriginalDelta.joinToString("\n"),
        )

    private fun trimPendingDrafts() {
        while (pendingDrafts.size > MAX_PENDING_DRAFTS) {
            pendingDrafts.removeAt(0)
        }
    }

    private fun compactFinalizedOriginal() {
        if (finalizedOriginalSegments.size > MAX_FINAL_SEGMENTS_RETAINED) {
            repeat(FINAL_SEGMENTS_TRIM_COUNT) {
                if (finalizedOriginalSegments.isNotEmpty()) {
                    finalizedOriginalSegments.removeAt(0)
                }
            }
        }
    }

    companion object {
        private const val MAX_PENDING_DRAFTS = 8
        private const val MAX_FINAL_SEGMENTS_IN_PROMPT = 50
        private const val MAX_FINAL_SEGMENTS_RETAINED = 100
        private const val FINAL_SEGMENTS_TRIM_COUNT = 50
    }
}

internal data class LiveInterpreterDraftChunk(
    val id: String,
    val sequenceNumber: Int,
    val text: String,
)

private data class LiveInterpreterFinalizedOriginal(
    val segmentId: String,
    val sequenceNumber: Int,
    val segments: List<String>,
)

internal data class LiveInterpreterStabilizerPromptContext(
    val finalizedOriginalTranscriptTail: String,
    val pendingDrafts: List<LiveInterpreterDraftChunk>,
)

internal data class TranscriptStabilizerResponse(
    val appendFinalOriginal: List<String> = emptyList(),
    val keepDraftIds: List<String> = emptyList(),
    val dropDraftIds: List<String> = emptyList(),
)

internal data class LiveInterpreterTranslationPromptContext(
    val finalizedOriginalTranscriptTail: String,
    val publishedTranslationTail: String,
    val newFinalOriginalDelta: String,
)

internal fun buildLiveInterpreterStabilizerSystemPrompt(sourceLanguageHint: String): String =
    """
    You stabilize noisy overlapping ASR drafts into a clean original-language live transcript.
    Source speech may mix $sourceLanguageHint.
    Do not translate. Preserve code-switching, names, acronyms, numbers, and technical terms.
    Drafts are overlapping Whisper hypotheses, so they can repeat, truncate, contradict, or contain partial phrases.
    Append only text that is now stable enough to publish as completed original transcript.
    Keep trailing incomplete or uncertain draft ids for the next pass.
    Drop draft ids that are fully covered by appended final text or are obsolete duplicates.
    Return exactly one XML document with this shape:
    <stabilization>
      <append>stable original-language sentence or clause to append</append>
      <keep>draft-id</keep>
      <drop>draft-id</drop>
    </stabilization>
    Repeat <append>, <keep>, and <drop> as needed. Omit empty sections.
    XML text content is raw text. Do not JSON-escape it.
    No markdown, no prose, no code fences.
    """.trimIndent()

internal fun buildLiveInterpreterStabilizerUserPrompt(context: LiveInterpreterStabilizerPromptContext): String =
    """
    Finalized original transcript tail:
    ${context.finalizedOriginalTranscriptTail.ifBlank { "(none yet)" }}

    Pending ASR drafts:
    ${context.pendingDrafts.joinToString("\n") { "[${it.id}] ${it.text}" }}

    Return XML:
    <stabilization>
      <append>stable original-language sentence or clause to append</append>
      <keep>draft id still needed because its tail is incomplete or uncertain</keep>
      <drop>draft id already covered or obsolete</drop>
    </stabilization>
    """.trimIndent()

internal fun buildLiveInterpreterTranslationSystemPrompt(
    targetLanguage: String,
    sourceLanguageHint: String,
): String =
    """
    You translate a live workplace transcript.
    Target language: ${targetLanguage.toTargetLanguageName()}.
    Source may mix $sourceLanguageHint.
    The source transcript was already stabilized from noisy ASR drafts, but speaker boundaries may still be missing.
    Use the finalized original transcript tail and the already published translation tail as context.
    Preserve names, product names, technical terms, numbers, and uncertainty.
    Return only new text that should be appended to the target-language transcript.
    Do not repeat already published translation. Do not explain.
    """.trimIndent()

internal fun buildLiveInterpreterTranslationUserPrompt(context: LiveInterpreterTranslationPromptContext): String =
    """
    Finalized original transcript tail:
    ${context.finalizedOriginalTranscriptTail}

    Already published translation tail:
    ${context.publishedTranslationTail.ifBlank { "(none yet)" }}

    New finalized original transcript delta to translate now:
    ${context.newFinalOriginalDelta}

    Return only the target-language text to append now.
    If this delta is too incomplete to safely translate, return an empty string.
    Do not repeat or rewrite the already published translation.
    """.trimIndent()

internal fun parseTranscriptStabilizerXml(text: String): TranscriptStabilizerResponse {
    val xml = text.extractXmlElement("stabilization")
    return TranscriptStabilizerResponse(
        appendFinalOriginal = xml.extractXmlElements("append"),
        keepDraftIds = xml.extractXmlElements("keep"),
        dropDraftIds = xml.extractXmlElements("drop"),
    )
}

private fun String.extractXmlElement(tag: String): String {
    val regex = Regex("<$tag\\b[^>]*>([\\s\\S]*?)</$tag>", RegexOption.IGNORE_CASE)
    return regex.find(removeCodeFence())?.groupValues?.get(1)?.trim()
        ?: error("Model response does not contain <$tag> XML")
}

private fun String.extractXmlElements(tag: String): List<String> {
    val regex = Regex("<$tag\\b[^>]*>([\\s\\S]*?)</$tag>", RegexOption.IGNORE_CASE)
    return regex.findAll(this)
        .map { it.groupValues[1].decodeBasicXmlEntities().trim() }
        .filter { it.isNotBlank() }
        .toList()
}

private fun String.removeCodeFence(): String =
    trim()
        .removePrefix("```xml")
        .removePrefix("```")
        .removeSuffix("```")
        .trim()

private fun String.decodeBasicXmlEntities(): String =
    replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
