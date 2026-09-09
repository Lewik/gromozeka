package com.gromozeka.presentation.services

import com.gromozeka.presentation.services.translation.LocalizedTextException
import com.gromozeka.presentation.services.translation.localizedText
import com.gromozeka.domain.model.SpeechAudioFormat
import com.gromozeka.shared.uuid.uuid7
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFAudio.AVAudioRecorder
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryRecord
import platform.AVFAudio.AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation
import platform.AVFAudio.AVFormatIDKey
import platform.AVFAudio.AVLinearPCMBitDepthKey
import platform.AVFAudio.AVLinearPCMIsBigEndianKey
import platform.AVFAudio.AVLinearPCMIsFloatKey
import platform.AVFAudio.AVNumberOfChannelsKey
import platform.AVFAudio.AVSampleRateKey
import platform.AVFAudio.setActive
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.posix.SEEK_END
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.rewind

private const val AUDIO_FORMAT_LINEAR_PCM = 1_819_304_813

@OptIn(ExperimentalForeignApi::class)
class IosClientAudioRecorder : ClientAudioRecorder {
    override suspend fun start(scope: CoroutineScope): ClientAudioRecordingSession {
        if (!requestMicrophonePermission()) {
            throw LocalizedTextException(localizedText("client.voice.microphoneDenied"))
        }

        configureAudioSession()
        val fileUrl = NSURL.fileURLWithPath("${NSTemporaryDirectory()}gromozeka-${uuid7()}.wav")
        try {
            val recorder = createRecorder(fileUrl)
            if (!recorder.record()) throw LocalizedTextException(localizedText("client.voice.microphoneInitializationFailed"))
            return IosClientAudioRecordingSession(recorder, fileUrl)
        } catch (error: Throwable) {
            deactivateAudioSession()
            runCatching { NSFileManager.defaultManager.removeItemAtURL(fileUrl, null) }
            throw error
        }
    }

    private suspend fun requestMicrophonePermission(): Boolean =
        suspendCancellableCoroutine { continuation: CancellableContinuation<Boolean> ->
            AVAudioSession.sharedInstance().requestRecordPermission { granted ->
                if (continuation.isActive) {
                    continuation.resume(granted) { _, _, _ -> }
                }
            }
        }

    @OptIn(ExperimentalForeignApi::class)
    private fun createRecorder(fileUrl: NSURL): AVAudioRecorder {
        val settings = mapOf<Any?, Any>(
            AVFormatIDKey to AUDIO_FORMAT_LINEAR_PCM,
            AVSampleRateKey to 16_000.0,
            AVNumberOfChannelsKey to 1,
            AVLinearPCMBitDepthKey to 16,
            AVLinearPCMIsBigEndianKey to false,
            AVLinearPCMIsFloatKey to false,
        )

        return AVAudioRecorder(fileUrl, settings, null)
    }

    private fun configureAudioSession() {
        val session = AVAudioSession.sharedInstance()
        session.setCategory(AVAudioSessionCategoryRecord, null)
        if (!session.setActive(active = true, error = null)) {
            throw LocalizedTextException(localizedText("client.voice.microphoneInitializationFailed"))
        }
    }

    private fun deactivateAudioSession() {
        AVAudioSession.sharedInstance().setActive(
            active = false,
            withOptions = AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation,
            error = null,
        )
    }
}

@OptIn(ExperimentalForeignApi::class)
private class IosClientAudioRecordingSession(
    private val recorder: AVAudioRecorder,
    private val fileUrl: NSURL,
) : ClientAudioRecordingSession {
    override suspend fun stop(): ClientRecordedAudio {
        recorder.stop()
        deactivateAudioSession()
        val path = fileUrl.path ?: throw LocalizedTextException(localizedText("client.voice.recordedFileMissing"))
        val data = readFileBytes(path)
        runCatching { NSFileManager.defaultManager.removeItemAtURL(fileUrl, null) }

        return ClientRecordedAudio(
            data = data,
            format = SpeechAudioFormat.WAV_PCM_S16LE_MONO_16_KHZ,
        )
    }

    override fun cancel() {
        recorder.stop()
        deactivateAudioSession()
        runCatching { NSFileManager.defaultManager.removeItemAtURL(fileUrl, null) }
    }

    private fun deactivateAudioSession() {
        AVAudioSession.sharedInstance().setActive(
            active = false,
            withOptions = AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation,
            error = null,
        )
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun readFileBytes(path: String): ByteArray {
    val file = fopen(path, "rb") ?: throw LocalizedTextException(localizedText("client.voice.recordedFileMissing"))
    try {
        if (fseek(file, 0, SEEK_END) != 0) throw LocalizedTextException(localizedText("client.voice.recordedFileMissing"))
        val length = ftell(file)
        if (length < 0 || length > Int.MAX_VALUE) {
            throw LocalizedTextException(localizedText("client.voice.recordedFileMissing"))
        }
        val size = length.toInt()
        rewind(file)
        if (size <= 0) {
            return ByteArray(0)
        }

        val output = ByteArray(size)
        output.usePinned { pinned ->
            if (fread(pinned.addressOf(0), 1u, size.toULong(), file) != size.toULong()) {
                throw LocalizedTextException(localizedText("client.voice.recordedFileMissing"))
            }
        }
        return output
    } finally {
        fclose(file)
    }
}
