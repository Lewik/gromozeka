package com.gromozeka.mobile.worker

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.gromozeka.worker.runtime.WorkerSoundVolumeOverride
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.sin

internal class AndroidWorkerSoundOutput(private val context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val volume = volumeOverride(context)

    suspend fun play(durationSeconds: Int, onStarted: () -> Unit): Nothing = volumeLifetime.withLock {
        withContext(Dispatchers.Main.immediate) {
            require(durationSeconds in 1..60) { "Sound duration must be between 1 and 60 seconds" }
            require(notifications.areNotificationsEnabled()) { "Allow Worker notifications before playing remote sound" }
            require(!audio.isVolumeFixed) { "This device does not allow alarm volume adjustment" }
            require(audio.mode == AudioManager.MODE_NORMAL) { "Device audio is busy with a call or communication" }
            requireAlarmsAllowed()
            requireBuiltInOutputs()
            val speaker = requireNotNull(audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }) { "A built-in speaker is required" }
            val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            val focusLost = CompletableDeferred<Unit>()
            val outputChanged = CompletableDeferred<Unit>()
            val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener({ change ->
                    if (change != AudioManager.AUDIOFOCUS_GAIN) focusLost.complete(Unit)
                }, Handler(Looper.getMainLooper()))
                .build()
            var track: AudioTrack? = null
            var focusRequested = false
            val deviceChanges = object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                    if (runCatching { requireBuiltInOutputs() }.isFailure) {
                        track?.setVolume(0f)
                        outputChanged.complete(Unit)
                    }
                }
            }
            val wakeLock = context.getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GromozekaWorker:loudSound")
            try {
                audio.registerAudioDeviceCallback(deviceChanges, Handler(Looper.getMainLooper()))
                volume.restore()
                currentCoroutineContext().ensureActive()
                focusRequested = true
                require(audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "Android denied audio focus" }
                val samples = alertSamples()
                val output = AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(samples.size * 2)
                    .build().also { track = it }
                require(output.state != AudioTrack.STATE_UNINITIALIZED) { "Android could not create alarm audio" }
                require(output.write(samples, 0, samples.size) == samples.size) { "Android could not load the alert sound" }
                require(output.state == AudioTrack.STATE_INITIALIZED) { "Android could not initialize alarm audio" }
                require(output.setPreferredDevice(speaker)) { "Android rejected the speaker output" }
                require(output.setVolume(0f) == AudioTrack.SUCCESS) { "Android could not prepare silent route verification" }
                require(output.setLoopPoints(0, samples.size, durationSeconds - 1) == AudioTrack.SUCCESS) { "Android could not bound the alert duration" }
                wakeLock.acquire((durationSeconds + 5) * 1_000L)
                output.play()
                repeat(10) {
                    if (output.routedDevice == null) delay(20)
                }
                requireSpeaker(output)
                requireBuiltInOutputs()
                currentCoroutineContext().ensureActive()
                require(!focusLost.isCompleted) { "Audio focus was lost before playback" }
                output.pause()
                require(output.reloadStaticData() == AudioTrack.SUCCESS) { "Android could not reset the prepared alert" }
                require(output.setLoopPoints(0, samples.size, durationSeconds - 1) == AudioTrack.SUCCESS) { "Android could not bound the alert duration" }
                volume.boost(audio.getStreamMaxVolume(AudioManager.STREAM_ALARM))
                requireBuiltInOutputs()
                require(!focusLost.isCompleted) { "Audio focus was lost before playback" }
                require(output.setVolume(1f) == AudioTrack.SUCCESS) { "Android could not start the alert sound" }
                output.play()
                onStarted()
                monitorPlayback(output, durationSeconds * SAMPLE_RATE, focusLost, outputChanged)
            } finally {
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    try {
                        audio.unregisterAudioDeviceCallback(deviceChanges)
                        val output = track
                        track = null
                        output?.release()
                    } finally {
                        try { if (focusRequested) audio.abandonAudioFocusRequest(focus) }
                        finally {
                            try { volume.restore() }
                            finally { if (wakeLock.isHeld) wakeLock.release() }
                        }
                    }
                }
            }
        }
    }

    private suspend fun monitorPlayback(output: AudioTrack, totalFrames: Int, focusLost: Deferred<Unit>, outputChanged: Deferred<Unit>): Nothing {
        while (true) {
            if (output.playbackHeadPosition >= totalFrames) awaitCancellation()
            requireSpeaker(output)
            require(!outputChanged.isCompleted) { "Sound stopped because an external audio output was connected" }
            requireBuiltInOutputs()
            require(!focusLost.isCompleted) { "Sound stopped because audio focus was lost" }
            require(audio.mode == AudioManager.MODE_NORMAL) { "Sound stopped because a call started" }
            requireAlarmsAllowed()
            require(!audio.isStreamMute(AudioManager.STREAM_ALARM) && audio.getStreamVolume(AudioManager.STREAM_ALARM) > 0) {
                "Sound stopped because the alarm stream was muted"
            }
            delay(100)
        }
    }

    private fun requireSpeaker(track: AudioTrack) {
        val routed = if (Build.VERSION.SDK_INT >= 36) track.routedDevices else listOfNotNull(track.routedDevice)
        require(routed.isNotEmpty() && routed.all { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }) {
            "Sound stopped because Android did not route it exclusively to the built-in speaker"
        }
    }

    private fun requireBuiltInOutputs() {
        require(hasOnlyBuiltInOutputs(audio)) { "Disconnect headphones and external audio outputs before playing a loud Worker alert" }
    }

    private fun requireAlarmsAllowed() {
        require(alarmsAllowed(notifications)) { "Do Not Disturb blocks this alert or its alarm policy cannot be verified; allow alarms in Android settings" }
    }

    private fun alertSamples() = ShortArray(SAMPLE_RATE) { index ->
        val phase = index % (SAMPLE_RATE / 2)
        val audibleFrames = SAMPLE_RATE / 3
        if (phase >= audibleFrames) 0 else {
            val envelope = minOf(1.0, phase / 240.0, (audibleFrames - phase) / 240.0)
            (sin(2 * PI * 880 * index / SAMPLE_RATE) * Short.MAX_VALUE * 0.8 * envelope).toInt().toShort()
        }
    }

    companion object {
        private const val SAMPLE_RATE = 24_000
        private val volumeLifetime = Mutex()

        private fun hasOnlyBuiltInOutputs(audio: AudioManager) = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).all {
            it.type in setOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE, AudioDeviceInfo.TYPE_TELEPHONY)
        }

        private fun alarmsAllowed(notifications: NotificationManager) = when (notifications.currentInterruptionFilter) {
            NotificationManager.INTERRUPTION_FILTER_ALL, NotificationManager.INTERRUPTION_FILTER_ALARMS -> true
            NotificationManager.INTERRUPTION_FILTER_PRIORITY -> Build.VERSION.SDK_INT >= 30 &&
                notifications.consolidatedNotificationPolicy.priorityCategories and NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS != 0
            else -> false
        }

        suspend fun recoverVolume(context: Context) = volumeLifetime.withLock {
            withContext(Dispatchers.Main.immediate) { volumeOverride(context).restore() }
        }

        private fun volumeOverride(context: Context): WorkerSoundVolumeOverride {
            val audio = context.getSystemService(AudioManager::class.java)
            val notifications = context.getSystemService(NotificationManager::class.java)
            val file = AndroidWorkerEncryptedFile(context.noBackupFilesDir.toPath().resolve("worker-sound-volume.enc"))
            return WorkerSoundVolumeOverride(
                readSnapshot = { withContext(Dispatchers.IO) { file.read() } },
                writeSnapshot = { value -> withContext(Dispatchers.IO) { file.write(value) } },
                currentVolume = {
                    if (alarmsAllowed(notifications) && hasOnlyBuiltInOutputs(audio)) audio.getStreamVolume(AudioManager.STREAM_ALARM) else null
                },
                setVolume = { audio.setStreamVolume(AudioManager.STREAM_ALARM, it, 0) },
            )
        }
    }
}
