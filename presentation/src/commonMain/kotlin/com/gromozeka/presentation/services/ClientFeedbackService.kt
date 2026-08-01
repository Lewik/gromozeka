package com.gromozeka.presentation.services

import com.gromozeka.client.RemoteClientPresentationService
import com.gromozeka.domain.model.Conversation
import com.gromozeka.remote.protocol.ClientFeedbackEffect
import com.gromozeka.remote.protocol.PlayClientFeedbackDirective
import klog.KLoggers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class ClientFeedbackService(
    private val clientPresentationService: RemoteClientPresentationService,
    private val soundNotificationPlayer: SoundNotificationPlayer,
    private val uiFeedbackController: UiFeedbackController,
    private val activeConversationId: () -> Conversation.Id?,
    scope: CoroutineScope,
) {
    private val log = KLoggers.logger(this)
    private val serviceJob = SupervisorJob(scope.coroutineContext[Job])
    private val serviceScope = CoroutineScope(scope.coroutineContext + serviceJob)
    private var directivesJob: Job? = null
    private var localFeedbackJob: Job? = null

    fun start() {
        localFeedbackJob = uiFeedbackController.events
            .onEach { event ->
                when (event) {
                    UiFeedbackEvent.ERROR -> soundNotificationPlayer.playErrorSound()
                }
            }
            .launchIn(serviceScope)
        directivesJob = clientPresentationService.directives
            .onEach { directive ->
                if (directive !is PlayClientFeedbackDirective) return@onEach
                when (directive.effect) {
                    ClientFeedbackEffect.ATTENTION ->
                        soundNotificationPlayer.playAttentionSound()
                    ClientFeedbackEffect.ACTIVITY ->
                        if (activeConversationId() == directive.conversationId) {
                            soundNotificationPlayer.playActivitySound()
                        }
                    ClientFeedbackEffect.ERROR -> uiFeedbackController.notifyError()
                }
            }
            .launchIn(serviceScope)
        log.info("Client feedback service started")
    }

    fun shutdown() {
        directivesJob?.cancel()
        localFeedbackJob?.cancel()
        directivesJob = null
        localFeedbackJob = null
        serviceScope.cancel()
    }
}
