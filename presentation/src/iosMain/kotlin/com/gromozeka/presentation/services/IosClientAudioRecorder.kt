package com.gromozeka.presentation.services

import com.gromozeka.shared.uuid.uuid7
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFAudio.AVAudioQualityHigh
import platform.AVFAudio.AVAudioRecorder
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVEncoderAudioQualityKey
import platform.AVFAudio.AVFormatIDKey
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

class IosClientAudioRecorder : ClientAudioRecorder {
    override suspend fun start(scope: CoroutineScope): ClientAudioRecordingSession {
        if (!requestMicrophonePermission()) {
            error("Microphone permission denied")
        }

        val fileUrl = NSURL.fileURLWithPath("${NSTemporaryDirectory()}gromozeka-${uuid7()}.m4a")
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
            AVFormatIDKey to 1_633_772_320,
            AVSampleRateKey to 16_000.0,
            AVNumberOfChannelsKey to 1,
            AVEncoderAudioQualityKey to AVAudioQualityHigh,
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
            mediaType = "audio/mp4",
            fileExtension = "m4a",
            byteSize = data.size,
            sampleRate = 16_000,
            channels = 1,
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
