package com.gromozeka.infrastructure.ai.openai

import com.gromozeka.domain.model.ai.AiAdmissionOutcome
import com.gromozeka.domain.model.ai.AiRuntimeOptions
import com.gromozeka.domain.service.AiCallAdmission
import com.gromozeka.domain.service.LiveTranscriptionEvent
import com.gromozeka.domain.service.LiveTranscriptionException
import com.gromozeka.domain.service.LiveTranscriptionSession
import com.gromozeka.domain.service.LiveTranscriptionTurnDetection
import com.gromozeka.domain.service.ResolvedAiRuntime
import com.gromozeka.infrastructure.ai.runtime.SdkHttpCallExecutor
import com.gromozeka.shared.audio.SpeechPcmWav
import com.openai.core.RequestOptions
import com.openai.models.audio.AudioResponseFormat
import com.openai.models.audio.transcriptions.TranscriptionCreateParams
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.Normalizer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToLong

private const val WHISPER_SAMPLE_RATE = 16_000
private const val BYTES_PER_SAMPLE = 2

internal data class WhisperWindowing(
    val windowSamples: Long = 20L * WHISPER_SAMPLE_RATE,
    val hopSamples: Long = 10L * WHISPER_SAMPLE_RATE,
    val maxBufferedSamples: Long = 120L * WHISPER_SAMPLE_RATE,
    val anchorToleranceSamples: Long = WHISPER_SAMPLE_RATE / 2L,
    val silenceRadiusSamples: Long = WHISPER_SAMPLE_RATE / 4L,
    val minimumAnchorWords: Int = 2,
    val minimumRepairAnchorWords: Int = 1,
) {
    init {
        require(windowSamples > 0 && hopSamples > 0) { "Whisper window and hop must be positive" }
        require(windowSamples == hopSamples * 2) { "Whisper Live requires exactly 50% overlap" }
        require(maxBufferedSamples >= windowSamples + hopSamples) { "Whisper Live buffer cannot hold a repair window" }
        require(minimumAnchorWords > 0 && minimumRepairAnchorWords in 1..minimumAnchorWords) {
            "Whisper anchor lengths are invalid"
        }
    }
}

internal data class WhisperWindowWord(
    val text: String,
    val startSample: Long,
    val endSample: Long,
)

internal class WhisperWindowTranscript(
    val startSample: Long,
    val endSample: Long,
    val text: String,
    words: List<WhisperWindowWord>,
) {
    internal data class Word(
        val text: String,
        val normalized: String,
        val startSample: Long,
        val endSample: Long,
        val textStart: Int,
        val textEnd: Int,
    ) {
        val midpoint: Long get() = startSample + (endSample - startSample) / 2L
    }

    val words: List<Word>

    init {
        require(startSample >= 0 && endSample >= startSample) { "Whisper window time range is invalid" }
        val lexical = words.filter { normalizeWord(it.text).isNotEmpty() }
        lexical.forEach { word ->
            require(word.startSample in startSample..endSample && word.endSample in word.startSample..endSample) {
                "Whisper word timestamp is outside its audio window"
            }
        }
        lexical.zipWithNext().forEach { (left, right) ->
            require(left.startSample <= right.startSample) { "Whisper word timestamps are not ordered" }
        }
        val normalizedText = normalizedTextWithPositions(text)
        val normalizedWords = lexical.joinToString(separator = "") { normalizeWord(it.text) }
        require(normalizedText.value == normalizedWords) {
            "Whisper transcript text does not match its word timestamps"
        }
        var normalizedOffset = 0
        this.words = lexical.mapIndexed { index, word ->
            val normalized = normalizeWord(word.text)
            val firstCharacter = if (index == 0) 0 else normalizedText.positions[normalizedOffset]
            normalizedOffset += normalized.length
            val nextCharacter = if (normalizedOffset < normalizedText.positions.size) {
                normalizedText.positions[normalizedOffset]
            } else {
                text.length
            }
            Word(
                text = word.text,
                normalized = normalized,
                startSample = word.startSample,
                endSample = word.endSample,
                textStart = firstCharacter,
                textEnd = nextCharacter,
            )
        }
    }

    val centerSample: Long get() = startSample + (endSample - startSample) / 2L

    fun textRange(fromIndex: Int, untilIndex: Int): String {
        require(fromIndex in 0..words.size && untilIndex in fromIndex..words.size) { "Invalid Whisper word range" }
        if (fromIndex == untilIndex) return ""
        return text.substring(words[fromIndex].textStart, words[untilIndex - 1].textEnd).trim()
    }

    private data class NormalizedText(val value: String, val positions: IntArray)

    private fun normalizedTextWithPositions(value: String): NormalizedText {
        val normalized = StringBuilder()
        val positions = ArrayList<Int>()
        value.forEachIndexed { index, character ->
            Normalizer.normalize(character.toString(), Normalizer.Form.NFKC).forEach { normalizedCharacter ->
                if (normalizedCharacter.isLetterOrDigit()) {
                    normalized.append(normalizedCharacter.lowercaseChar())
                    positions += index
                }
            }
        }
        return NormalizedText(normalized.toString(), positions.toIntArray())
    }
}

internal sealed interface WhisperWindowMerge {
    data class Direct(val previousUntil: Int, val nextFrom: Int) : WhisperWindowMerge
    data class RepairRequired(val boundarySample: Long) : WhisperWindowMerge
}

internal data class WhisperRepairMerge(
    val previousUntil: Int,
    val repairFrom: Int,
    val repairUntil: Int,
    val nextFrom: Int,
)

internal class WhisperWindowReconciler(private val settings: WhisperWindowing = WhisperWindowing()) {
    private data class Match(val left: Int, val right: Int)
    private data class MatchRun(val matches: List<Match>)

    fun merge(previous: WhisperWindowTranscript, next: WhisperWindowTranscript): WhisperWindowMerge {
        require(previous.startSample < next.startSample && next.startSample < previous.endSample) {
            "Whisper windows must overlap in chronological order"
        }
        val boundary = (previous.centerSample + next.centerSample) / 2L
        if (hasCommonSilence(previous, next, boundary)) {
            return WhisperWindowMerge.Direct(
                previousUntil = firstWordAtOrAfter(previous, boundary),
                nextFrom = firstWordAtOrAfter(next, boundary),
            )
        }
        val runs = matchingRuns(previous, next)
            .filter { it.matches.size >= settings.minimumAnchorWords }
        val crossing = runs.filter { run ->
            val start = averageMidpoint(previous, next, run.matches.first())
            val end = averageMidpoint(previous, next, run.matches.last())
            boundary >= start - settings.anchorToleranceSamples &&
                boundary <= end + settings.anchorToleranceSamples
        }
        val run = crossing.minByOrNull { run ->
            run.matches.minOf { match -> kotlin.math.abs(averageMidpoint(previous, next, match) - boundary) }
        } ?: return WhisperWindowMerge.RepairRequired(boundary)
        val pivot = run.matches.minBy { match -> kotlin.math.abs(averageMidpoint(previous, next, match) - boundary) }
        return WhisperWindowMerge.Direct(previousUntil = pivot.left + 1, nextFrom = pivot.right + 1)
    }

    fun mergeRepair(
        previous: WhisperWindowTranscript,
        repair: WhisperWindowTranscript,
        next: WhisperWindowTranscript,
        boundarySample: Long,
    ): WhisperRepairMerge {
        require(repair.startSample <= previous.startSample && repair.endSample >= next.endSample) {
            "Whisper repair window must cover both source windows"
        }
        val leftRuns = matchingRuns(previous, repair)
            .filter { it.matches.size >= settings.minimumRepairAnchorWords }
            .filter { averageMidpoint(previous, repair, it.matches.last()) <= boundarySample }
        val rightRuns = matchingRuns(repair, next)
            .filter { it.matches.size >= settings.minimumRepairAnchorWords }
            .filter { averageMidpoint(repair, next, it.matches.first()) >= boundarySample }
        val left = leftRuns.maxByOrNull { averageMidpoint(previous, repair, it.matches.last()) }
        val right = rightRuns.minByOrNull { averageMidpoint(repair, next, it.matches.first()) }
        val (previousUntil, repairFrom) = if (left != null) {
            val pivot = left.matches.last()
            pivot.left + 1 to pivot.right + 1
        } else {
            val silence = commonSilenceBoundary(previous, repair, previous.startSample, boundarySample, boundarySample)
                ?: throw LiveTranscriptionException("Whisper repair has no confirmed left audio anchor", retryable = false)
            firstWordAtOrAfter(previous, silence) to firstWordAtOrAfter(repair, silence)
        }
        val (repairUntil, nextFrom) = if (right != null) {
            val pivot = right.matches.last()
            pivot.left + 1 to pivot.right + 1
        } else {
            val silence = commonSilenceBoundary(repair, next, boundarySample, next.endSample, boundarySample)
                ?: throw LiveTranscriptionException("Whisper repair has no confirmed right audio anchor", retryable = false)
            firstWordAtOrAfter(repair, silence) to firstWordAtOrAfter(next, silence)
        }
        require(repairFrom <= repairUntil) { "Whisper repair anchors overlap" }
        return WhisperRepairMerge(previousUntil, repairFrom, repairUntil, nextFrom)
    }

    private fun hasCommonSilence(
        previous: WhisperWindowTranscript,
        next: WhisperWindowTranscript,
        boundary: Long,
    ): Boolean {
        val from = boundary - settings.silenceRadiusSamples
        val until = boundary + settings.silenceRadiusSamples
        fun WhisperWindowTranscript.hasSpeech() = words.any { it.endSample > from && it.startSample < until }
        return !previous.hasSpeech() && !next.hasSpeech()
    }

    private fun commonSilenceBoundary(
        left: WhisperWindowTranscript,
        right: WhisperWindowTranscript,
        requestedStart: Long,
        requestedEnd: Long,
        preferred: Long,
    ): Long? {
        val start = maxOf(left.startSample, right.startSample, requestedStart)
        val end = minOf(left.endSample, right.endSample, requestedEnd)
        if (end - start < settings.silenceRadiusSamples * 2L) return null
        val speech = (left.words.asSequence() + right.words.asSequence())
            .map { maxOf(start, it.startSample) to minOf(end, it.endSample) }
            .filter { (wordStart, wordEnd) -> wordStart <= wordEnd && wordEnd >= start && wordStart <= end }
            .sortedBy { it.first }
            .toList()
        val candidates = mutableListOf<Long>()
        var cursor = start
        fun addGap(gapStart: Long, gapEnd: Long) {
            val safeStart = gapStart + settings.silenceRadiusSamples
            val safeEnd = gapEnd - settings.silenceRadiusSamples
            if (safeStart <= safeEnd) candidates += preferred.coerceIn(safeStart, safeEnd)
        }
        speech.forEach { (wordStart, wordEnd) ->
            if (wordStart > cursor) addGap(cursor, wordStart)
            cursor = maxOf(cursor, wordEnd)
        }
        if (cursor < end) addGap(cursor, end)
        return candidates.minByOrNull { kotlin.math.abs(it - preferred) }
    }

    private fun firstWordAtOrAfter(transcript: WhisperWindowTranscript, sample: Long): Int =
        transcript.words.indexOfFirst { it.midpoint >= sample }.let { if (it < 0) transcript.words.size else it }

    private fun matchingRuns(left: WhisperWindowTranscript, right: WhisperWindowTranscript): List<MatchRun> {
        val overlapStart = maxOf(left.startSample, right.startSample)
        val overlapEnd = minOf(left.endSample, right.endSample)
        val leftIndices = left.words.indices.filter { left.words[it].midpoint in overlapStart until overlapEnd }
        val rightIndices = right.words.indices.filter { right.words[it].midpoint in overlapStart until overlapEnd }
        val pairs = longestCommonSubsequence(left, right, leftIndices, rightIndices)
        if (pairs.isEmpty()) return emptyList()
        val runs = mutableListOf<MutableList<Match>>()
        pairs.forEach { match ->
            val current = runs.lastOrNull()
            if (current == null || current.last().left + 1 != match.left || current.last().right + 1 != match.right) {
                runs += mutableListOf(match)
            } else {
                current += match
            }
        }
        return runs.map(::MatchRun)
    }

    private fun longestCommonSubsequence(
        left: WhisperWindowTranscript,
        right: WhisperWindowTranscript,
        leftIndices: List<Int>,
        rightIndices: List<Int>,
    ): List<Match> {
        val table = Array(leftIndices.size + 1) { IntArray(rightIndices.size + 1) }
        for (i in leftIndices.indices.reversed()) {
            for (j in rightIndices.indices.reversed()) {
                table[i][j] = if (wordsMatch(left, right, leftIndices[i], rightIndices[j])) {
                    1 + table[i + 1][j + 1]
                } else {
                    maxOf(table[i + 1][j], table[i][j + 1])
                }
            }
        }
        val result = mutableListOf<Match>()
        var i = 0
        var j = 0
        while (i < leftIndices.size && j < rightIndices.size) {
            if (wordsMatch(left, right, leftIndices[i], rightIndices[j])) {
                result += Match(leftIndices[i], rightIndices[j])
                i += 1
                j += 1
            } else if (table[i + 1][j] >= table[i][j + 1]) {
                i += 1
            } else {
                j += 1
            }
        }
        return result
    }

    private fun wordsMatch(
        left: WhisperWindowTranscript,
        right: WhisperWindowTranscript,
        leftIndex: Int,
        rightIndex: Int,
    ): Boolean {
        val leftWord = left.words[leftIndex]
        val rightWord = right.words[rightIndex]
        return leftWord.normalized == rightWord.normalized &&
            kotlin.math.abs(leftWord.midpoint - rightWord.midpoint) <= settings.anchorToleranceSamples
    }

    private fun averageMidpoint(
        left: WhisperWindowTranscript,
        right: WhisperWindowTranscript,
        match: Match,
    ): Long = (left.words[match.left].midpoint + right.words[match.right].midpoint) / 2L
}

internal fun interface WhisperWindowTranscriber {
    suspend fun transcribe(startSample: Long, pcmLittleEndian: ByteArray): WhisperWindowTranscript
}

internal class OpenAiWhisperWindowTranscriber(
    private val runtime: ResolvedAiRuntime,
    private val clientFactory: OpenAiSdkClientFactory,
    private val admission: AiCallAdmission,
    private val languageHints: List<String>,
) : WhisperWindowTranscriber {
    override suspend fun transcribe(startSample: Long, pcmLittleEndian: ByteArray): WhisperWindowTranscript {
        val wav = SpeechPcmWav.encode(pcmLittleEndian)
        val tempFile = File.createTempFile("gromozeka-live-whisper", ".wav")
        try {
            tempFile.writeBytes(wav)
            val params = TranscriptionCreateParams.builder()
                .file(tempFile.toPath())
                .model(runtime.modelConfiguration.providerModelId)
                .responseFormat(AudioResponseFormat.VERBOSE_JSON)
                .addTimestampGranularity(TranscriptionCreateParams.TimestampGranularity.WORD)
                .temperature(0.0)
                .apply {
                    if (languageHints.size == 1) language(languageHints.single())
                }
                .build()
            val executor = SdkHttpCallExecutor(
                runtime.connection,
                runtime.modelConfiguration.defaultParameters.timeoutSeconds,
                admission = admission,
                modelConfiguration = runtime.modelConfiguration,
            ) { end -> clientFactory.createInferenceClient(runtime.connection, end) }
            val response = executor.call(AiRuntimeOptions(usagePurpose = "LIVE_TRANSCRIPTION")) { client, remaining, _ ->
                client.async().audio().transcriptions().create(
                    params,
                    RequestOptions.builder().timeout(remaining).build(),
                ).thenApply { it.asVerbose() }
            }
            val durationSamples = pcmLittleEndian.size.toLong() / BYTES_PER_SAMPLE
            val words = response.words().orElseThrow {
                LiveTranscriptionException("Whisper did not return word timestamps", retryable = true)
            }.map { word ->
                val localStart = (word.start() * WHISPER_SAMPLE_RATE).roundToLong().coerceIn(0L, durationSamples)
                val localEnd = (word.end() * WHISPER_SAMPLE_RATE).roundToLong().coerceIn(localStart, durationSamples)
                WhisperWindowWord(word.word(), startSample + localStart, startSample + localEnd)
            }
            return WhisperWindowTranscript(
                startSample = startSample,
                endSample = startSample + durationSamples,
                text = response.text(),
                words = words,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: LiveTranscriptionException) {
            throw error
        } catch (error: Exception) {
            throw LiveTranscriptionException("Whisper Live transcription request failed", retryable = true, cause = error)
        } finally {
            tempFile.delete()
        }
    }
}

internal class OpenAiWhisperWindowedTranscriptionSession(
    private val transcriber: WhisperWindowTranscriber,
    private val settings: WhisperWindowing = WhisperWindowing(),
    private val reconciler: WhisperWindowReconciler = WhisperWindowReconciler(settings),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : LiveTranscriptionSession {
    override val turnDetection: LiveTranscriptionTurnDetection = LiveTranscriptionTurnDetection.PROVIDER
    private val eventChannel = Channel<LiveTranscriptionEvent>(128)
    override val events: Flow<LiveTranscriptionEvent> = eventChannel.receiveAsFlow()
    private val requests = Channel<WindowRequest>(4)
    private val bufferMutex = Mutex()
    private val buffer = PcmBuffer()
    private val closed = AtomicBoolean(false)
    private val finishing = AtomicBoolean(false)
    private val failure = AtomicReference<Exception?>(null)
    private var nextRegularStart = 0L
    private var lastScheduledStart: Long? = null
    private var processor: Job? = null
    private var previousItemId: String? = null
    private var nextItemNumber = 0L

    private data class WindowRequest(val startSample: Long, val endSample: Long, val pcm: ByteArray)
    private data class CurrentWindow(
        val transcript: WhisperWindowTranscript,
        val fromWord: Int,
        val consumedThroughSample: Long,
    )

    fun start() {
        check(processor == null) { "Whisper Live session was started twice" }
        processor = scope.launch {
            try {
                processRequests()
            } catch (error: CancellationException) {
                if (!closed.get()) fail(error)
                throw error
            } catch (error: Exception) {
                fail(error)
            }
        }
    }

    override suspend fun appendAudio(pcm: ByteArray) {
        failure.get()?.let { throw it }
        check(!finishing.get()) { "Live input is already finishing" }
        require(pcm.size % BYTES_PER_SAMPLE == 0) { "Whisper PCM16 chunks must contain complete samples" }
        if (pcm.isEmpty()) return
        val littleEndian = pcm.copyOf()
        for (index in littleEndian.indices step 2) {
            val high = littleEndian[index]
            littleEndian[index] = littleEndian[index + 1]
            littleEndian[index + 1] = high
        }
        bufferMutex.withLock {
            buffer.append(littleEndian)
            if (buffer.sizeSamples > settings.maxBufferedSamples) {
                throw LiveTranscriptionException("Whisper Live audio buffer overflow; transcription is too far behind", retryable = true)
            }
            scheduleRegularWindows()
        }
    }

    override suspend fun commit() {
        error("Whisper Live owns its overlapping audio windows")
    }

    override suspend fun finishInput() {
        failure.get()?.let { throw it }
        check(finishing.compareAndSet(false, true)) { "Live input is already finishing" }
        bufferMutex.withLock {
            scheduleRegularWindows()
            scheduleFinalWindow()
            requests.close()
        }
        processor?.join()
        failure.get()?.let { throw it }
        eventChannel.close()
    }

    override suspend fun close(outcome: AiAdmissionOutcome) = withContext(NonCancellable) {
        if (!closed.compareAndSet(false, true)) return@withContext
        requests.cancel()
        scope.cancel()
        processor?.join()
        eventChannel.close(failure.get())
    }

    private fun scheduleRegularWindows() {
        while (buffer.endSample >= nextRegularStart + settings.windowSamples) {
            schedule(nextRegularStart, nextRegularStart + settings.windowSamples)
            nextRegularStart += settings.hopSamples
        }
    }

    private fun scheduleFinalWindow() {
        val end = buffer.endSample
        if (end == 0L) return
        val finalStart = maxOf(0L, end - settings.windowSamples)
        if (lastScheduledStart != finalStart) schedule(finalStart, end)
    }

    private fun schedule(startSample: Long, endSample: Long) {
        val request = WindowRequest(startSample, endSample, buffer.slice(startSample, endSample))
        if (!requests.trySend(request).isSuccess) {
            throw LiveTranscriptionException("Whisper Live window queue overflow", retryable = true)
        }
        lastScheduledStart = startSample
    }

    private suspend fun processRequests() {
        var current: CurrentWindow? = null
        for (request in requests) {
            val nextTranscript = transcriber.transcribe(request.startSample, request.pcm)
            require(nextTranscript.startSample == request.startSample && nextTranscript.endSample == request.endSample) {
                "Whisper returned a transcript for the wrong audio range"
            }
            val previous = current
            if (previous == null) {
                current = CurrentWindow(nextTranscript, 0, nextTranscript.startSample)
                continue
            }
            when (val merge = reconciler.merge(previous.transcript, nextTranscript)) {
                is WhisperWindowMerge.Direct -> {
                    val previousUntil = maxOf(previous.fromWord, merge.previousUntil)
                    emitRange(previous.transcript, previous.fromWord, previousUntil)
                    val consumedThrough = maxOf(
                        previous.consumedThroughSample,
                        consumedThrough(previous.transcript, previousUntil),
                        consumedThrough(nextTranscript, merge.nextFrom),
                    )
                    val nextFrom = maxOf(merge.nextFrom, firstWordAtOrAfter(nextTranscript, consumedThrough))
                    current = CurrentWindow(nextTranscript, nextFrom, consumedThrough)
                }
                is WhisperWindowMerge.RepairRequired -> {
                    val repairPcm = bufferMutex.withLock {
                        buffer.slice(previous.transcript.startSample, nextTranscript.endSample)
                    }
                    val repair = transcriber.transcribe(previous.transcript.startSample, repairPcm)
                    val repaired = reconciler.mergeRepair(previous.transcript, repair, nextTranscript, merge.boundarySample)
                    val previousUntil = maxOf(previous.fromWord, repaired.previousUntil)
                    emitRange(previous.transcript, previous.fromWord, previousUntil)
                    val repairFrom = maxOf(
                        repaired.repairFrom,
                        firstWordAtOrAfter(repair, previous.consumedThroughSample),
                    )
                    val repairUntil = maxOf(repairFrom, repaired.repairUntil)
                    emitRange(repair, repairFrom, repairUntil)
                    val consumedThrough = maxOf(
                        previous.consumedThroughSample,
                        consumedThrough(previous.transcript, previousUntil),
                        consumedThrough(repair, repairUntil),
                        consumedThrough(nextTranscript, repaired.nextFrom),
                    )
                    val nextFrom = maxOf(repaired.nextFrom, firstWordAtOrAfter(nextTranscript, consumedThrough))
                    current = CurrentWindow(nextTranscript, nextFrom, consumedThrough)
                }
            }
            bufferMutex.withLock { buffer.discardBefore(nextTranscript.startSample) }
        }
        current?.let { emitRange(it.transcript, it.fromWord, it.transcript.words.size) }
    }

    private fun firstWordAtOrAfter(transcript: WhisperWindowTranscript, sample: Long): Int =
        transcript.words.indexOfFirst { it.midpoint >= sample }.let { if (it < 0) transcript.words.size else it }

    private fun consumedThrough(transcript: WhisperWindowTranscript, untilWord: Int): Long {
        require(untilWord in 0..transcript.words.size) { "Invalid consumed Whisper word range" }
        return if (untilWord == 0) transcript.startSample else transcript.words[untilWord - 1].endSample
    }

    private suspend fun emitRange(transcript: WhisperWindowTranscript, fromWord: Int, untilWord: Int) {
        val text = transcript.textRange(fromWord, untilWord)
        if (text.isBlank()) return
        val itemId = "whisper-${nextItemNumber++}"
        eventChannel.send(LiveTranscriptionEvent.Committed(itemId, previousItemId))
        eventChannel.send(LiveTranscriptionEvent.Transcript(itemId, text, isFinal = true))
        previousItemId = itemId
    }

    private fun fail(error: Exception) {
        if (closed.get() || !failure.compareAndSet(null, error)) return
        requests.close(error)
        eventChannel.close(error)
    }

    private class PcmBuffer {
        private val output = ByteArrayOutputStream()
        var startSample: Long = 0L
            private set
        val sizeSamples: Long get() = output.size().toLong() / BYTES_PER_SAMPLE
        val endSample: Long get() = startSample + sizeSamples

        fun append(pcmLittleEndian: ByteArray) {
            output.write(pcmLittleEndian)
        }

        fun slice(fromSample: Long, untilSample: Long): ByteArray {
            require(fromSample >= startSample && untilSample in fromSample..endSample) { "Whisper audio range is no longer buffered" }
            val bytes = output.toByteArray()
            val from = ((fromSample - startSample) * BYTES_PER_SAMPLE).toInt()
            val until = ((untilSample - startSample) * BYTES_PER_SAMPLE).toInt()
            return bytes.copyOfRange(from, until)
        }

        fun discardBefore(sample: Long) {
            if (sample <= startSample) return
            require(sample <= endSample) { "Cannot discard beyond buffered Whisper audio" }
            val bytes = output.toByteArray()
            val from = ((sample - startSample) * BYTES_PER_SAMPLE).toInt()
            output.reset()
            output.write(bytes, from, bytes.size - from)
            startSample = sample
        }
    }
}

private fun normalizeWord(value: String): String = buildString {
    Normalizer.normalize(value, Normalizer.Form.NFKC).forEach { character ->
        if (character.isLetterOrDigit()) append(character.lowercaseChar())
    }
}
