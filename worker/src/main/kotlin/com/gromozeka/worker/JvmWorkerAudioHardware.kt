package com.gromozeka.worker

import com.gromozeka.domain.model.SpeechAudioFormat
import com.gromozeka.domain.model.WorkerAudioInput
import com.gromozeka.shared.audio.SpeechPcmWav
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.Mixer
import javax.sound.sampled.TargetDataLine
import klog.KLoggers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

internal object JvmWorkerAudioHardware {
    private val log = KLoggers.logger(this)
    private val format = AudioFormat(16_000f, 16, 1, true, false)
    private val lineInfo = DataLine.Info(TargetDataLine::class.java, format)

    fun inputs(): List<WorkerAudioInput> = runCatching { discoverInputs() }
        .onFailure { error ->
            log.warn(error) { "Worker audio input discovery failed: ${error.message}" }
        }
        .getOrDefault(emptyList())

    private fun discoverInputs(): List<WorkerAudioInput> = buildList {
        if (AudioSystem.isLineSupported(lineInfo)) {
            add(WorkerAudioInput.SystemDefault)
        }
        AudioSystem.getMixerInfo()
            .mapNotNull { info ->
                runCatching { AudioSystem.getMixer(info) }
                    .getOrNull()
                    ?.takeIf { it.isLineSupported(lineInfo) }
                    ?.let {
                        WorkerAudioInput(
                            id = WorkerAudioInput.Id("mixer-${info.stableId()}"),
                            displayName = info.displayName(),
                        )
                    }
            }
            .distinctBy { it.id }
            .sortedBy { it.displayName.lowercase() }
            .forEach(::add)
    }

    suspend fun start(
        scope: CoroutineScope,
        inputId: WorkerAudioInput.Id,
    ): WorkerAudioCaptureSession = withContext(Dispatchers.IO) {
        val line = when (inputId) {
            WorkerAudioInput.SystemDefault.id -> AudioSystem.getLine(lineInfo) as TargetDataLine
            else -> resolveMixer(inputId).getLine(lineInfo) as TargetDataLine
        }
        try {
            line.open(format)
            line.start()
            WorkerAudioCaptureSession(scope, line).also(WorkerAudioCaptureSession::start)
        } catch (error: Throwable) {
            runCatching { line.close() }
            throw error
        }
    }

    private fun resolveMixer(inputId: WorkerAudioInput.Id): Mixer =
        AudioSystem.getMixerInfo()
            .firstOrNull { WorkerAudioInput.Id("mixer-${it.stableId()}") == inputId }
            ?.let(AudioSystem::getMixer)
            ?: error("Worker audio input is not available: ${inputId.value}")

    private fun Mixer.Info.displayName(): String =
        listOf(name.trim(), vendor.trim())
            .filter(String::isNotBlank)
            .distinct()
            .joinToString(" - ")
            .ifBlank { "Audio input" }

    private fun Mixer.Info.stableId(): String =
        MessageDigest.getInstance("SHA-256")
            .digest("$name\u0000$vendor\u0000$description\u0000$version".encodeToByteArray())
            .take(12)
            .joinToString("") { "%02x".format(it) }
}

internal class WorkerAudioCaptureSession(
    private val scope: CoroutineScope,
    private val line: TargetDataLine,
) {
    private val stopping = AtomicBoolean(false)
    private val rawAudio = ByteArrayOutputStream()
    private lateinit var recordingJob: Deferred<Unit>

    fun start() {
        recordingJob = scope.async(Dispatchers.IO) {
            val chunk = ByteArray(2_048)
            while (isActive && !stopping.get()) {
                val count = try {
                    line.read(chunk, 0, chunk.size)
                } catch (error: Throwable) {
                    if (stopping.get()) break
                    throw error
                }
                if (count > 0) rawAudio.write(chunk, 0, count)
            }
        }
    }

    suspend fun stop(): CapturedWorkerAudio {
        closeLine()
        recordingJob.await()
        val wav = SpeechPcmWav.encode(rawAudio.toByteArray())
        return CapturedWorkerAudio(wav, SpeechAudioFormat.WAV_PCM_S16LE_MONO_16_KHZ)
    }

    suspend fun cancel() {
        closeLine()
        recordingJob.join()
    }

    private fun closeLine() {
        if (!stopping.compareAndSet(false, true)) return
        runCatching { line.stop() }
        runCatching { line.close() }
    }
}

internal data class CapturedWorkerAudio(
    val bytes: ByteArray,
    val format: SpeechAudioFormat,
)
