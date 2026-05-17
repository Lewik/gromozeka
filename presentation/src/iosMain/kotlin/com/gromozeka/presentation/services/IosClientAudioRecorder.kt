package com.gromozeka.presentation.services

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
import platform.AVFAudio.AVFormatIDKey
import platform.AVFAudio.AVLinearPCMBitDepthKey
import platform.AVFAudio.AVLinearPCMIsBigEndianKey
import platform.AVFAudio.AVLinearPCMIsFloatKey
import platform.AVFAudio.AVNumberOfChannelsKey
import platform.AVFAudio.AVSampleRateKey
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
            error("Microphone permission denied")
        }

        AVAudioSession.sharedInstance().setCategory(AVAudioSessionCategoryRecord, null)
        val fileUrl = NSURL.fileURLWithPath("${NSTemporaryDirectory()}gromozeka-${uuid7()}.wav")
        val recorder = createRecorder(fileUrl)
        check(recorder.record()) { "Failed to start iOS audio recorder" }

        return IosClientAudioRecordingSession(recorder, fileUrl)
    }

    private suspend fun requestMicrophonePermission(): Boolean =
        suspendCancellableCoroutine { continuation: CancellableContinuation<Boolean> ->
            AVAudioSession.sharedInstance().requestRecordPermission { granted ->
                if (continuation.isActive) {
                    continuation.resume(granted, onCancellation = null)
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
            ?: error("Failed to create iOS audio recorder")
    }
}

@OptIn(ExperimentalForeignApi::class)
private class IosClientAudioRecordingSession(
    private val recorder: AVAudioRecorder,
    private val fileUrl: NSURL,
) : ClientAudioRecordingSession {
    override suspend fun stop(): ClientRecordedAudio {
        recorder.stop()
        val path = fileUrl.path ?: error("Recorded iOS audio file path is missing")
        val data = readFileBytes(path)
        runCatching { NSFileManager.defaultManager.removeItemAtURL(fileUrl, null) }

        return ClientRecordedAudio(
            data = data,
            mediaType = "audio/wav",
            fileExtension = "wav",
            byteSize = data.size,
            sampleRate = 16_000,
            channels = 1,
            bitDepth = 16,
        )
    }

    override fun cancel() {
        recorder.stop()
        runCatching { NSFileManager.defaultManager.removeItemAtURL(fileUrl, null) }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun readFileBytes(path: String): ByteArray {
    val file = fopen(path, "rb") ?: error("Recorded iOS audio file is not readable")
    try {
        check(fseek(file, 0, SEEK_END) == 0) { "Failed to seek recorded iOS audio file" }
        val size = ftell(file).toInt()
        rewind(file)
        if (size <= 0) {
            return ByteArray(0)
        }

        val output = ByteArray(size)
        output.usePinned { pinned ->
            fread(pinned.addressOf(0), 1u, size.toULong(), file)
        }
        return output
    } finally {
        fclose(file)
    }
}
