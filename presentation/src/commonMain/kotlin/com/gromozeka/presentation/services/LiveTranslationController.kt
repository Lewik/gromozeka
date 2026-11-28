package com.gromozeka.presentation.services

import com.gromozeka.client.LiveInterpreterClient
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import com.gromozeka.domain.model.ai.AiUsage
import com.gromozeka.presentation.services.translation.LocalizedText
import com.gromozeka.presentation.services.translation.localizedText
import com.gromozeka.remote.protocol.LiveTextTranslatedResponse
import com.gromozeka.remote.protocol.LiveTranslationSourceBlock
import com.gromozeka.remote.protocol.TranslateLiveTextRequest
import com.gromozeka.shared.uuid.uuid7
import klog.KLoggers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlin.math.max

internal data class LiveSourceBlock(
    val id: String,
    val text: String,
    val segmentIds: Set<String>,
    val speakerId: String? = null,
    val closed: Boolean = false,
)

internal data class LiveTranslationValue(val sourceText: String, val text: String)

internal data class LiveTranslationColumn(
    val values: Map<String, LiveTranslationValue> = emptyMap(),
    val fixedThrough: Int = -1,
    val contextStart: Int = 0,
    val error: LocalizedText? = null,
    val latestUsage: AiUsage? = null,
    val activation: Int = 0,
)

internal data class LiveTranslationGap(val id: String, val beforeBlock: Int, val message: LocalizedText)
internal data class PendingLiveFinal(val id: String, val text: String, val speakerId: String?)

internal data class LiveTranslationState(
    val sessionId: String = uuid7(),
    val generation: Int = 0,
    val languages: List<String> = emptyList(),
    val languageSelectionExplicit: Boolean = false,
    val nativeLanguage: String? = null,
    val modelId: AiModelConfiguration.Id? = null,
    val blocks: List<LiveSourceBlock> = emptyList(),
    val gaps: List<LiveTranslationGap> = emptyList(),
    val sourceGapBoundaries: Set<Int> = emptySet(),
    val columns: Map<String, LiveTranslationColumn> = emptyMap(),
    val consumedSegments: Set<String> = emptySet(),
    val activeLanguage: String? = null,
    val captureFinished: Boolean = false,
    val sourceError: LocalizedText? = null,
    val epoch: Int = 0,
    val nextSequence: Int = 0,
    val pendingFinals: Map<Int, PendingLiveFinal> = emptyMap(),
) {
    fun isFixed(language: String, index: Int): Boolean =
        columns[language]?.let { index <= it.fixedThrough && it.values[blocks[index].id]?.sourceText == blocks[index].text } == true
}

internal class LiveTranslationController(
    private val client: LiveInterpreterClient,
    scope: CoroutineScope,
    initialLanguages: List<String>,
    private val blockCharacters: Int = 1_000,
    private val revisableBlocks: Int = 2,
    private val batchCharacters: Int = 8_000,
    private val contextHighCharacters: Int = 64_000,
    private val contextLowCharacters: Int = 32_000,
    private val retainedSourceCharacters: Int = 2_400,
) {
    private val log = KLoggers.logger(this)
    private val mutableState = MutableStateFlow(LiveTranslationState(languages = normalizeLanguages(initialLanguages)))
    val state = mutableState.asStateFlow()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var nextRevision = 0
    private var lastLanguage: String? = null
    private val job: Job

    init {
        require(blockCharacters >= 32 && batchCharacters >= blockCharacters * (revisableBlocks + 1))
        require(revisableBlocks >= 1 && retainedSourceCharacters > 0)
        require(contextHighCharacters > contextLowCharacters && contextLowCharacters > 0)
        job = scope.launch {
            try {
                for (signal in wake) {
                    while (isActive) {
                        val snapshot = state.value
                        if (snapshot.sourceError != null) break
                        val language = nextLanguage(snapshot) ?: break
                        lastLanguage = language
                        try {
                            val request = request(snapshot, language)
                            if (!reserve(snapshot, language)) continue
                            val response = client.translate(request)
                            accept(snapshot, request, response)
                        } catch (error: Exception) {
                            if (error is CancellationException && !currentCoroutineContext().isActive) throw error
                            log.warn { "Live translation paused: language=$language type=${error::class.simpleName}" }
                            mutableState.update { current ->
                                if (current.generation != snapshot.generation || current.sourceError != null ||
                                    (current.columns[language]?.activation ?: 0) != (snapshot.columns[language]?.activation ?: 0)) current
                                else current.copy(columns = current.columns + (language to
                                    (current.columns[language] ?: LiveTranslationColumn()).copy(
                                        error = localizedText("interpreter.translationFailed", "error" to error.localizedText()),
                                    )))
                            }
                        } finally {
                            mutableState.update { it.copy(activeLanguage = null) }
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                stopSourceStage(error)
            }
        }
    }

    fun setLanguages(languages: List<String>) {
        val normalized = normalizeLanguages(languages)
        mutableState.update { withLanguages(it, normalized, explicit = true) }
        wake.trySend(Unit)
    }

    fun toggleLanguage(language: String) {
        val code = normalizeLanguages(listOf(language)).single()
        mutableState.update { current ->
            val selected = if (code in current.languages) current.languages - code
                else if (current.languages.size < 16) current.languages + code else return@update current
            withLanguages(current, selected, explicit = true)
        }
        wake.trySend(Unit)
    }

    fun addLanguage(language: String) {
        val code = normalizeLanguages(listOf(language)).single()
        mutableState.update { current ->
            if (code in current.languages || current.languages.size >= 16) current
            else withLanguages(current, current.languages + code, explicit = true)
        }
        wake.trySend(Unit)
    }

    fun useInterfaceLanguage(language: String) {
        val normalized = normalizeLanguages(listOf(language))
        mutableState.update { current ->
            if (current.languageSelectionExplicit) current else withLanguages(current, normalized, explicit = false)
        }
        wake.trySend(Unit)
    }

    fun setNativeLanguage(language: String?) {
        val normalized = language?.let { normalizeLanguages(listOf(it)).single() }
        mutableState.update { current -> current.copy(nativeLanguage = normalized) }
        wake.trySend(Unit)
    }

    private fun withLanguages(current: LiveTranslationState, languages: List<String>, explicit: Boolean): LiveTranslationState =
        current.copy(languages = languages, languageSelectionExplicit = explicit,
            columns = current.columns + languages.associateWith { language ->
                val old = current.columns[language] ?: LiveTranslationColumn()
                if (language in current.languages) old else old.copy(error = null, activation = old.activation + 1)
            })

    fun selectModel(id: AiModelConfiguration.Id) {
        mutableState.update { current ->
            if (current.modelId == id) current else current.copy(
                modelId = id, generation = current.generation + 1, columns = emptyMap(),
            )
        }
        wake.trySend(Unit)
    }

    fun retry(language: String) {
        mutableState.update { current ->
            val column = current.columns[language] ?: LiveTranslationColumn()
            current.copy(columns = current.columns +
                (language to column.copy(error = null, activation = column.activation + 1)))
        }
        wake.trySend(Unit)
    }

    fun reset() {
        mutableState.update { current -> LiveTranslationState(
            generation = current.generation + 1, languages = current.languages, nativeLanguage = current.nativeLanguage,
            modelId = current.modelId, languageSelectionExplicit = current.languageSelectionExplicit,
        ) }
        wake.trySend(Unit)
    }

    fun beginEpoch(epoch: Int) = changeSource { current ->
        check(epoch > current.epoch && current.pendingFinals.isEmpty()) { "Live translation epoch is inconsistent" }
        current.closeLastBlock().copy(epoch = epoch, nextSequence = 0, captureFinished = false)
    }

    fun acceptFinal(epoch: Int, sequence: Int, id: String, text: String, speakerId: String? = null) = changeSource { current ->
        check(!current.captureFinished && epoch == current.epoch && sequence >= current.nextSequence && sequence !in current.pendingFinals) {
            "Live translation final position is inconsistent"
        }
        var next = current.copy(pendingFinals = current.pendingFinals + (sequence to PendingLiveFinal(id, text, speakerId)))
        while (true) {
            val item = next.pendingFinals[next.nextSequence] ?: break
            next = append(next, item).copy(
                pendingFinals = next.pendingFinals - next.nextSequence,
                nextSequence = next.nextSequence + 1,
            )
        }
        next
    }

    fun gap(id: String, message: LocalizedText) = changeSource { current ->
        var next = current
        // On an interrupted ASR connection, keep every known final even if an earlier turn never completed.
        var position = current.nextSequence
        current.pendingFinals.entries.sortedBy { it.key }.forEach { (sequence, final) ->
            next = next.closeLastBlock()
            if (sequence > position) next = next.copy(sourceGapBoundaries = next.sourceGapBoundaries + next.blocks.size)
            next = append(next, final).closeLastBlock()
            position = sequence + 1
        }
        next.closeLastBlock().copy(
            pendingFinals = emptyMap(),
            nextSequence = (current.pendingFinals.keys.maxOrNull()?.plus(1)) ?: current.nextSequence,
            gaps = next.gaps + LiveTranslationGap(id, next.blocks.size, message),
            sourceGapBoundaries = next.sourceGapBoundaries + next.blocks.size,
        )
    }

    fun finish() = changeSource { current ->
        check(current.pendingFinals.isEmpty()) { "Live translation ended with missing final positions" }
        val closed = current.closeLastBlock().copy(captureFinished = true)
        closed.copy(columns = closed.columns.mapValues { (language, column) ->
            if (firstPending(closed, column) < 0 && column.error == null) column.copy(fixedThrough = closed.blocks.lastIndex)
            else column
        })
    }

    fun cancel() { job.cancel(); wake.close() }

    private fun changeSource(transform: (LiveTranslationState) -> LiveTranslationState) {
        if (state.value.sourceError != null) return
        try {
            mutableState.update { current -> if (current.sourceError == null) transform(current) else current }
            wake.trySend(Unit)
        } catch (error: Exception) {
            stopSourceStage(error)
        }
    }

    private fun stopSourceStage(error: Exception) {
        log.warn { "Live translation input stage stopped: type=${error::class.simpleName}" }
        mutableState.update { it.copy(activeLanguage = null,
            sourceError = localizedText("interpreter.translationInputFailed", "error" to error.localizedText())) }
    }

    private fun reserve(snapshot: LiveTranslationState, language: String): Boolean {
        while (true) {
            val current = state.value
            if (current.generation != snapshot.generation || current.sourceError != null || language !in current.languages ||
                (current.columns[language]?.activation ?: 0) != (snapshot.columns[language]?.activation ?: 0)) return false
            if (mutableState.compareAndSet(current, current.copy(activeLanguage = language))) return true
        }
    }

    private fun nextLanguage(snapshot: LiveTranslationState): String? {
        if (snapshot.languages.isEmpty()) return null
        val start = (snapshot.languages.indexOf(lastLanguage) + 1) % snapshot.languages.size
        return (snapshot.languages.indices).map { snapshot.languages[(start + it) % snapshot.languages.size] }
            .firstOrNull { language ->
                val column = snapshot.columns[language] ?: LiveTranslationColumn()
                language != snapshot.nativeLanguage && column.error == null && firstPending(snapshot, column) >= 0
            }
    }

    private fun firstPending(snapshot: LiveTranslationState, column: LiveTranslationColumn): Int =
        snapshot.blocks.indexOfFirst { column.values[it.id]?.sourceText != it.text }

    private fun request(snapshot: LiveTranslationState, language: String): TranslateLiveTextRequest {
        val column = snapshot.columns[language] ?: LiveTranslationColumn()
        val pending = firstPending(snapshot, column)
        check(pending >= 0 && pending > column.fixedThrough) { "A fixed Live translation block became pending" }
        val start = max(column.fixedThrough + 1, pending - revisableBlocks)
        val selected = mutableListOf<LiveTranslationSourceBlock>()
        var characters = 0
        for (index in start..snapshot.blocks.lastIndex) {
            val block = snapshot.blocks[index]
            if (selected.size >= 32 || (selected.isNotEmpty() && characters + block.text.length > batchCharacters)) break
            selected += LiveTranslationSourceBlock(block.id, block.text, column.values[block.id]?.text,
                gapBefore = index in snapshot.sourceGapBoundaries)
            characters += block.text.length
        }
        check(selected.any { block -> block.id == snapshot.blocks[pending].id }) { "Live translation batch cannot advance" }
        val context = if (column.fixedThrough < column.contextStart) emptyList() else
            (column.contextStart..column.fixedThrough).map { index ->
                val block = snapshot.blocks[index]
                val value = column.values.getValue(block.id)
                check(value.sourceText == block.text) { "A fixed Live translation source was modified" }
                LiveTranslationSourceBlock(block.id, block.text, value.text,
                    gapBefore = index in snapshot.sourceGapBoundaries)
            }
        return TranslateLiveTextRequest(
            sessionId = snapshot.sessionId, revision = nextRevision++, targetLanguage = language,
            context = context, blocks = selected,
            runtimeSelection = snapshot.modelId?.let(::AiRuntimeSelection),
        )
    }

    private fun accept(snapshot: LiveTranslationState, request: TranslateLiveTextRequest, response: LiveTextTranslatedResponse) {
        check(response.revision == request.revision && response.targetLanguage == request.targetLanguage) {
            "Live translation response binding is inconsistent"
        }
        check(response.blocks.map { it.id }.toSet() == request.blocks.map { it.id }.toSet() &&
            response.blocks.size == request.blocks.size) { "Live translation response coverage is inconsistent" }
        val translations = response.blocks.associateBy { it.id }
        mutableState.update { current ->
            if (current.generation != snapshot.generation || current.sessionId != request.sessionId || current.sourceError != null) return@update current
            val old = current.columns[request.targetLanguage] ?: LiveTranslationColumn()
            val indices = current.blocks.withIndex().associate { it.value.id to it.index }
            val values = old.values + request.blocks.associate { source ->
                val index = indices.getValue(source.id)
                check(index > old.fixedThrough && current.blocks[index].text.startsWith(source.text)) {
                    "Live translation tried to replace a fixed or incompatible block"
                }
                source.id to LiveTranslationValue(source.text, translations.getValue(source.id).text)
            }
            val completed = current.blocks.indexOfFirst { values[it.id]?.sourceText != it.text }
                .let { if (it < 0) current.blocks.lastIndex else it - 1 }
            var fixed = if (current.captureFinished && completed == current.blocks.lastIndex) completed
                else max(old.fixedThrough, completed - revisableBlocks)
            while (fixed > old.fixedThrough && !current.blocks[fixed].closed) fixed -= 1
            var updated = old.copy(values = values, fixedThrough = fixed, error = null, latestUsage = response.usage)
            updated = updated.copy(contextStart = trimContext(current, updated))
            current.copy(columns = current.columns + (request.targetLanguage to updated))
        }
    }

    private fun trimContext(snapshot: LiveTranslationState, column: LiveTranslationColumn): Int {
        if (column.fixedThrough < column.contextStart) return column.contextStart
        var start = column.contextStart
        var characters = (start..column.fixedThrough).sumOf { index ->
            snapshot.blocks[index].text.length + column.values.getValue(snapshot.blocks[index].id).text.length + 128
        }
        if (characters <= contextHighCharacters) return start
        var sourceCharacters = (start..column.fixedThrough).sumOf { snapshot.blocks[it].text.length }
        while (start < column.fixedThrough && characters > contextLowCharacters) {
            val block = snapshot.blocks[start]
            if (sourceCharacters - block.text.length < retainedSourceCharacters) break
            characters -= block.text.length + column.values.getValue(block.id).text.length + 128
            sourceCharacters -= block.text.length
            start += 1
        }
        return start
    }

    private fun append(current: LiveTranslationState, final: PendingLiveFinal): LiveTranslationState {
        check(final.id !in current.consumedSegments) { "Live translation source was appended twice" }
        var next = current.copy(consumedSegments = current.consumedSegments + final.id)
        if (final.text.isBlank()) return next
        val open = next.blocks.lastOrNull()?.takeUnless { it.closed }
        if (open != null && open.speakerId != final.speakerId) next = next.closeLastBlock()
        var remaining = final.text
        var firstPiece = true
        while (remaining.isNotEmpty()) {
            if (!firstPiece && remaining.isBlank()) {
                val tail = next.blocks.last()
                next = next.copy(blocks = next.blocks.dropLast(1) + tail.copy(text = tail.text + remaining))
                break
            }
            val last = next.blocks.lastOrNull()?.takeUnless { it.closed }
            val separator = if (firstPiece && last != null) "\n" else ""
            val capacity = blockCharacters - (last?.text?.length ?: 0) - separator.length
            val keepUtteranceWhole = firstPiece && last != null && remaining.length <= blockCharacters && remaining.length > capacity
            if (capacity <= 1 || keepUtteranceWhole) { next = next.closeLastBlock(); continue }
            var take = minOf(capacity, remaining.length)
            if (take < remaining.length) {
                val punctuation = charArrayOf('.', '!', '?', '\n', '。', '！', '？', '؟')
                var boundary = remaining.lastIndexOfAny(punctuation, take - 1)
                while (boundary >= take / 2 && remaining[boundary] == '.' && boundary + 1 < remaining.length &&
                    !remaining[boundary + 1].isWhitespace()) {
                    boundary = remaining.lastIndexOfAny(punctuation, boundary - 1)
                }
                if (boundary >= take / 2) take = boundary + 1 else {
                    val space = remaining.lastIndexOfAny(charArrayOf(' ', '\n', '\t'), take - 1)
                    if (space >= take / 2) take = space + 1
                }
                if (take > 0 && remaining[take - 1] in '\uD800'..'\uDBFF' && remaining[take] in '\uDC00'..'\uDFFF') take -= 1
            }
            if (remaining.take(take).isBlank()) take = remaining.indexOfFirst { !it.isWhitespace() } + 1
            check(take in 1..12_000) { "Live translation source splitting did not advance within its block limit" }
            val piece = remaining.take(take)
            val block = if (last == null) LiveSourceBlock("block-${next.blocks.size}", piece, setOf(final.id), final.speakerId) else
                last.copy(text = last.text + separator + piece, segmentIds = last.segmentIds + final.id)
            next = next.copy(blocks = (if (last == null) next.blocks else next.blocks.dropLast(1)) + block)
            remaining = remaining.drop(take)
            firstPiece = false
            if (remaining.isNotEmpty() || next.blocks.last().text.length >= blockCharacters) next = next.closeLastBlock()
        }
        return next
    }

    private fun LiveTranslationState.closeLastBlock(): LiveTranslationState =
        if (blocks.isEmpty() || blocks.last().closed) this else copy(blocks = blocks.dropLast(1) + blocks.last().copy(closed = true))

    private fun normalizeLanguages(languages: List<String>): List<String> = languages.map { it.trim().lowercase() }.distinct().also {
        require(it.size <= 16 && it.all { code -> code.matches(Regex("[a-z]{2,3}(-[a-z0-9]{2,8})*")) }) {
            "Expected translation language codes such as he, en, ru"
        }
    }
}
