package com.gromozeka.presentation.services

import com.gromozeka.client.RemoteClientPresentationService
import com.gromozeka.domain.model.TtsTask
import com.gromozeka.domain.service.SettingsService
import com.gromozeka.remote.protocol.AssistantMessageSignal
import com.gromozeka.remote.protocol.PlayClientFeedbackDirective
import com.gromozeka.remote.protocol.PresentAssistantMessageDirective
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

class AssistantAudioPresentationService(
    private val clientPresentationService: RemoteClientPresentationService,
    private val ttsQueueService: TtsQueue,
    private val settingsService: SettingsService,
    private val soundNotificationPlayer: SoundNotificationPlayer,
    scope: CoroutineScope,
) {
    private val log = KLoggers.logger(this)
    private val serviceJob = SupervisorJob(scope.coroutineContext[Job])
    private val serviceScope = CoroutineScope(scope.coroutineContext + serviceJob)
    private val playbackJobsMutex = Mutex()
    private val playbackJobs = mutableSetOf<Job>()
    private var playbackTail: Job? = null
    private var directivesJob: Job? = null

    fun start() {
        log.info("Starting assistant audio presentation service")
        directivesJob = clientPresentationService.directives
            .onEach { directive ->
                when (directive) {
                    is PresentAssistantMessageDirective -> schedulePresentation(directive)
                    is PlayClientFeedbackDirective -> Unit
                    StopTtsDirective -> stopPlayback()
                }
            }
            .launchIn(serviceScope)
    }

    private suspend fun schedulePresentation(directive: PresentAssistantMessageDirective) {
        val playbackJob = playbackJobsMutex.withLock {
            val previous = playbackTail
            serviceScope.launch(start = CoroutineStart.LAZY) {
                previous?.join()
                playPresentation(directive)
            }.also { job ->
                playbackTail = job
                playbackJobs += job
            }
        }
        playbackJob.invokeOnCompletion {
            serviceScope.launch {
                playbackJobsMutex.withLock {
                    playbackJobs -= playbackJob
                    if (playbackTail === playbackJob) {
                        playbackTail = null
                    }
                }
            }
        }
        playbackJob.start()
    }

    private suspend fun playPresentation(directive: PresentAssistantMessageDirective) {
        when (directive.signal) {
            AssistantMessageSignal.ATTENTION -> soundNotificationPlayer.playAttentionSound()
            AssistantMessageSignal.ACTIVITY -> soundNotificationPlayer.playActivitySound()
        }

        val speech = directive.speech ?: return
        if (!settingsService.settings.userProfile.speechSettings.textToSpeech.enabled) {
            log.info { "Auto TTS skipped because it is disabled: message=${directive.messageId.value}" }
            return
        }

        log.info {
            "Auto TTS enqueue: message=${directive.messageId.value} textChars=${speech.text.length} " +
                "voiceToneChars=${speech.tone.length}"
        }
        ttsQueueService.enqueue(TtsTask(speech.text, speech.tone))
    }

    private suspend fun stopPlayback() {
        val jobs = playbackJobsMutex.withLock {
            playbackTail = null
            playbackJobs.toList().also { playbackJobs.clear() }
        }
        jobs.forEach(Job::cancel)
        ttsQueueService.stopAndClear()
        log.info { "Assistant audio presentation stopped because the active client changed" }
    }

    fun shutdown() {
        log.info("Shutting down assistant audio presentation service")
        directivesJob?.cancel()
        directivesJob = null
        serviceJob.cancel()
    }
}
