package com.gromozeka.presentation.services

import com.gromozeka.client.RemoteClientPresentationService
import com.gromozeka.domain.service.SettingsService
import com.gromozeka.domain.model.TtsTask
import com.gromozeka.remote.protocol.PlayMessageTtsDirective
import com.gromozeka.remote.protocol.StopTtsDirective
import klog.KLoggers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TTSAutoplayService(
    private val clientPresentationService: RemoteClientPresentationService,
    private val ttsQueueService: TtsQueue,
    private val settingsService: SettingsService,
    private val scope: CoroutineScope,
) {
    private val log = KLoggers.logger(this)
    private val serviceJob = SupervisorJob(scope.coroutineContext[Job])
    private val serviceScope = CoroutineScope(scope.coroutineContext + serviceJob)
    private val playbackJobsMutex = Mutex()
    private val playbackJobs = mutableSetOf<Job>()
    private var directivesJob: Job? = null

    fun start() {
        log.info("Starting auto TTS service")
        directivesJob = clientPresentationService.directives
            .onEach { directive ->
                when (directive) {
                    is PlayMessageTtsDirective -> schedulePlayback(directive)
                    StopTtsDirective -> stopPlayback()
                }
            }
            .launchIn(serviceScope)
    }

    private suspend fun schedulePlayback(directive: PlayMessageTtsDirective) {
        if (!settingsService.settings.userProfile.speechSettings.textToSpeech.enabled) {
            log.info { "Auto TTS skipped because it is disabled: message=${directive.messageId.value}" }
            return
        }

        val playbackJob = serviceScope.launch(
            start = CoroutineStart.LAZY,
        ) {
            log.info {
                "Auto TTS enqueue: message=${directive.messageId.value} textChars=${directive.text.length} " +
                    "voiceToneChars=${directive.tone.length}"
            }
            ttsQueueService.enqueue(TtsTask(directive.text, directive.tone))
        }
        playbackJobsMutex.withLock {
            playbackJobs += playbackJob
        }
        playbackJob.invokeOnCompletion {
            serviceScope.launch {
                playbackJobsMutex.withLock {
                    playbackJobs -= playbackJob
                }
            }
        }
        playbackJob.start()
    }

    private suspend fun stopPlayback() {
        val jobs = playbackJobsMutex.withLock {
            playbackJobs.toList().also { playbackJobs.clear() }
        }
        jobs.forEach(Job::cancel)
        ttsQueueService.stopAndClear()
        log.info { "Auto TTS stopped because the active client changed" }
    }

    fun shutdown() {
        log.info("Shutting down auto TTS service")
        directivesJob?.cancel()
        directivesJob = null
        serviceJob.cancel()
    }
}
