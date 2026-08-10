package com.gromozeka.server

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.User
import com.gromozeka.application.service.ContextStateApplicationService
import com.gromozeka.domain.model.ClientActivity as ContextClientActivity
import com.gromozeka.domain.model.ClientPlatform as ContextClientPlatform
import com.gromozeka.remote.protocol.AssistantMessageSignal
import com.gromozeka.remote.protocol.AssistantMessageSpeech
import com.gromozeka.remote.protocol.ClientFeedbackEffect
import com.gromozeka.remote.protocol.ClientActivityKind
import com.gromozeka.remote.protocol.ClientSessionId
import com.gromozeka.remote.protocol.PlayClientFeedbackDirective
import com.gromozeka.remote.protocol.PresentAssistantMessageDirective
import com.gromozeka.remote.protocol.RegisterClientSessionCommand
import com.gromozeka.remote.protocol.RemoteProtocolEncoding
import com.gromozeka.remote.protocol.ServerPayload
import com.gromozeka.remote.protocol.StopTtsDirective
import klog.KLoggers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import org.springframework.stereotype.Service

internal typealias ClientPresentationSend = suspend (ServerPayload, RemoteProtocolEncoding) -> Unit

@Service
class ClientPresentationRegistry(
    private val activityListeners: List<ClientActivityListener> = emptyList(),
) {
    private val log = KLoggers.logger(this)
    private val mutex = Mutex()
    private val deliveryMutex = Mutex()
    private val sessionsByKey = mutableMapOf<ClientSessionKey, RegisteredClientSession>()
    private val sessionKeysByConnection = mutableMapOf<String, ClientSessionKey>()
    private val presentedEventKeys = LinkedHashSet<PresentedEventKey>()
    private val activeSessionKeysByUser = mutableMapOf<User.Id, ClientSessionKey>()
    private val lastActivityPresentationAt = LinkedHashMap<ActivityRateKey, Long>()

    suspend fun register(
        userId: User.Id,
        connectionId: String,
        command: RegisterClientSessionCommand,
        encoding: RemoteProtocolEncoding,
        send: ClientPresentationSend,
    ) = deliveryMutex.withLock {
        require(command.clientInstanceId.value.isNotBlank()) { "Client instance ID must not be blank" }
        require(command.clientSessionId.value.isNotBlank()) { "Client session ID must not be blank" }

        val reconnected = mutex.withLock {
            val sessionKey = ClientSessionKey(userId, command.clientSessionId)
            sessionKeysByConnection.put(connectionId, sessionKey)
                ?.takeIf { it != sessionKey }
                ?.let { previousSessionKey ->
                    sessionsByKey[previousSessionKey]
                        ?.takeIf { it.connectionId == connectionId }
                        ?.let { sessionsByKey.remove(previousSessionKey) }
                }
            val current = RegisteredClientSession(
                connectionId = connectionId,
                userId = userId,
                identity = command,
                encoding = encoding,
                send = send,
            )
            sessionsByKey.put(
                sessionKey,
                current,
            )?.let { previous ->
                sessionKeysByConnection.remove(previous.connectionId)
            }
            sessionKeysByConnection[connectionId] = sessionKey
            current.takeIf { activeSessionKeysByUser[userId] == sessionKey }
        }
        log.info {
            "Client presentation session registered: user=${userId.value} instance=${command.clientInstanceId.value} " +
                "session=${command.clientSessionId.value} platform=${command.platform}"
        }
        reconnected?.let { session ->
            activityListeners.forEach { listener ->
                listener.onActivity(
                    userId = session.userId,
                    identity = session.identity,
                    activity = ContextClientActivity.RECONNECTED,
                    active = true,
                )
            }
        }
    }

    suspend fun updateEncoding(connectionId: String, encoding: RemoteProtocolEncoding) {
        mutex.withLock {
            val sessionKey = sessionKeysByConnection[connectionId] ?: return@withLock
            sessionsByKey[sessionKey]?.encoding = encoding
        }
    }

    suspend fun requireRegistered(connectionId: String) {
        check(mutex.withLock { connectionId in sessionKeysByConnection }) {
            "Client session must be registered before sending other payloads"
        }
    }

    suspend fun activate(connectionId: String, kind: ClientActivityKind) {
        deliveryMutex.withLock {
            activateAndStopPrevious(connectionId, kind)
        }
    }

    private suspend fun activateAndStopPrevious(connectionId: String, kind: ClientActivityKind) {
        val activation = mutex.withLock {
            val sessionKey = sessionKeysByConnection[connectionId]
                ?: error("Client session must be registered before reporting activity")
            if (activeSessionKeysByUser[sessionKey.userId] == sessionKey) {
                return@withLock null
            }

            val previous = activeSessionKeysByUser[sessionKey.userId]?.let(sessionsByKey::get)
            Activation(
                sessionKey = sessionKey,
                current = sessionsByKey.getValue(sessionKey),
                previous = previous,
            )
        } ?: return

        activityListeners.forEach { listener ->
            listener.onActivity(
                userId = activation.current.userId,
                identity = activation.current.identity,
                activity = kind.toContextActivity(),
                active = true,
            )
        }
        mutex.withLock {
            check(sessionsByKey[activation.sessionKey] === activation.current) {
                "Client session changed while activating"
            }
            activeSessionKeysByUser[activation.current.userId] = activation.sessionKey
        }
        log.info {
            "Active interaction client changed: user=${activation.current.userId.value} " +
                "instance=${activation.current.identity.clientInstanceId.value} " +
                "session=${activation.current.identity.clientSessionId.value} kind=$kind"
        }
        activation.previous?.let { previous ->
            try {
                previous.send(StopTtsDirective, previous.encoding)
            } catch (error: Throwable) {
                log.warn(error) {
                    "Failed to stop TTS on previous active client: " +
                        "session=${previous.identity.clientSessionId.value} error=${error.message}"
                }
            }
        }
    }

    suspend fun disconnect(connectionId: String) = deliveryMutex.withLock {
        val disconnection = mutex.withLock {
            val sessionKey = sessionKeysByConnection.remove(connectionId) ?: return@withLock null
            val session = sessionsByKey[sessionKey]
                ?.takeIf { it.connectionId == connectionId }
                ?: return@withLock null
            val wasActive = activeSessionKeysByUser[sessionKey.userId] == sessionKey
            sessionsByKey.remove(sessionKey)
            Disconnection(session, wasActive)
        } ?: return

        val disconnected = disconnection.session

        log.info {
            "Client presentation session disconnected: user=${disconnected.userId.value} " +
                "instance=${disconnected.identity.clientInstanceId.value} " +
                "session=${disconnected.identity.clientSessionId.value}"
        }
        if (disconnection.wasActive) {
            activityListeners.forEach { listener ->
                listener.onActivity(
                    userId = disconnected.userId,
                    identity = disconnected.identity,
                    activity = ContextClientActivity.DISCONNECTED,
                    active = false,
                )
            }
        }
    }

    suspend fun present(userId: User.Id, message: Conversation.Message): Boolean =
        deliveryMutex.withLock {
            presentAssistantMessageToActiveClient(userId, message)
        }

    suspend fun presentError(
        userId: User.Id,
        conversationId: Conversation.Id,
        eventKey: String,
    ): Boolean = deliveryMutex.withLock {
        presentSoundToActiveClient(
            userId = userId,
            eventKey = "error:$eventKey",
            conversationId = conversationId,
            effect = ClientFeedbackEffect.ERROR,
        )
    }

    suspend fun presentActivity(
        userId: User.Id,
        conversationId: Conversation.Id,
        eventKey: String,
    ): Boolean = deliveryMutex.withLock {
        reserveActivityPresentation(userId, conversationId) && presentSoundToActiveClient(
            userId = userId,
            eventKey = "activity:$eventKey",
            conversationId = conversationId,
            effect = ClientFeedbackEffect.ACTIVITY,
        )
    }

    private suspend fun presentAssistantMessageToActiveClient(
        userId: User.Id,
        message: Conversation.Message,
    ): Boolean {
        val presentation = message.assistantPresentation() ?: return false
        val target = claimTarget(userId, "message:${message.id.value}") ?: return false
        return try {
            target.send(
                PresentAssistantMessageDirective(
                    messageId = message.id,
                    conversationId = message.conversationId,
                    signal = if (presentation.attentionRequested) {
                        AssistantMessageSignal.ATTENTION
                    } else {
                        AssistantMessageSignal.ACTIVITY
                    },
                    speech = presentation.speech?.let { AssistantMessageSpeech(it.text, it.tone) },
                ),
                target.encoding,
            )
            log.info {
                "Assistant presentation routed: user=${userId.value} message=${message.id.value} " +
                    "speech=${presentation.speech != null} attention=${presentation.attentionRequested} " +
                    "instance=${target.identity.clientInstanceId.value} session=${target.identity.clientSessionId.value}"
            }
            true
        } catch (error: Throwable) {
            log.warn(error) {
                "Failed to route assistant presentation: message=${message.id.value} " +
                    "session=${target.identity.clientSessionId.value} error=${error.message}"
            }
            false
        }
    }

    private suspend fun presentSoundToActiveClient(
        userId: User.Id,
        eventKey: String,
        conversationId: Conversation.Id,
        effect: ClientFeedbackEffect,
    ): Boolean {
        val target = claimTarget(userId, eventKey) ?: return false
        return try {
            target.send(
                PlayClientFeedbackDirective(
                    conversationId = conversationId,
                    effect = effect,
                ),
                target.encoding,
            )
            true
        } catch (error: Throwable) {
            log.warn(error) {
                "Failed to route UI sound: event=$eventKey effect=$effect " +
                    "session=${target.identity.clientSessionId.value} error=${error.message}"
            }
            false
        }
    }

    private suspend fun claimTarget(userId: User.Id, eventKey: String): RegisteredClientSession? {
        val claim = mutex.withLock {
            val firstPresentation = presentedEventKeys.add(PresentedEventKey(userId, eventKey))
            trimPresentedEventKeys()
            if (!firstPresentation) {
                PresentationClaim.Duplicate
            } else {
                activeSessionKeysByUser[userId]
                    ?.let(sessionsByKey::get)
                    ?.let(PresentationClaim::Target)
                    ?: PresentationClaim.NoActiveClient
            }
        }

        return when (claim) {
            PresentationClaim.Duplicate -> null
            PresentationClaim.NoActiveClient -> {
                log.info { "Presentation has no active client: user=${userId.value} event=$eventKey" }
                null
            }
            is PresentationClaim.Target -> claim.session
        }
    }

    private fun trimPresentedEventKeys() {
        while (presentedEventKeys.size > MAX_PRESENTED_EVENT_KEYS) {
            presentedEventKeys.remove(presentedEventKeys.first())
        }
    }

    private fun Conversation.Message.assistantPresentation(): AssistantPresentation? {
        if (role != Conversation.Message.Role.ASSISTANT) {
            return null
        }
        val structured = content
            .filterIsInstance<Conversation.Message.ContentItem.AssistantMessage>()
            .firstOrNull()
            ?.structured
            ?: return null
        val speech = structured.ttsText
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let { AssistantSpeech(it, structured.voiceTone.orEmpty()) }
        return AssistantPresentation(speech, structured.attentionRequested)
    }

    private suspend fun reserveActivityPresentation(
        userId: User.Id,
        conversationId: Conversation.Id,
    ): Boolean {
        val now = Clock.System.now().toEpochMilliseconds()
        return mutex.withLock {
            val rateKey = ActivityRateKey(userId, conversationId)
            val previous = lastActivityPresentationAt[rateKey]
            if (previous != null && now - previous < ACTIVITY_SOUND_INTERVAL_MILLIS) {
                false
            } else {
                lastActivityPresentationAt[rateKey] = now
                while (lastActivityPresentationAt.size > MAX_ACTIVITY_RATE_KEYS) {
                    lastActivityPresentationAt.remove(lastActivityPresentationAt.keys.first())
                }
                true
            }
        }
    }

    private data class RegisteredClientSession(
        val connectionId: String,
        val userId: User.Id,
        val identity: RegisterClientSessionCommand,
        var encoding: RemoteProtocolEncoding,
        val send: ClientPresentationSend,
    )

    private data class ClientSessionKey(
        val userId: User.Id,
        val sessionId: ClientSessionId,
    )

    private data class PresentedEventKey(
        val userId: User.Id,
        val eventKey: String,
    )

    private data class ActivityRateKey(
        val userId: User.Id,
        val conversationId: Conversation.Id,
    )

    private data class Activation(
        val sessionKey: ClientSessionKey,
        val current: RegisteredClientSession,
        val previous: RegisteredClientSession?,
    )

    private data class Disconnection(
        val session: RegisteredClientSession,
        val wasActive: Boolean,
    )

    private data class AssistantSpeech(
        val text: String,
        val tone: String,
    )

    private data class AssistantPresentation(
        val speech: AssistantSpeech?,
        val attentionRequested: Boolean,
    )

    private sealed interface PresentationClaim {
        data object Duplicate : PresentationClaim
        data object NoActiveClient : PresentationClaim
        data class Target(val session: RegisteredClientSession) : PresentationClaim
    }

    private companion object {
        const val MAX_PRESENTED_EVENT_KEYS = 10_000
        const val MAX_ACTIVITY_RATE_KEYS = 10_000
        const val ACTIVITY_SOUND_INTERVAL_MILLIS = 1_750L
    }
}

fun interface ClientActivityListener {
    suspend fun onActivity(
        userId: User.Id,
        identity: RegisterClientSessionCommand,
        activity: ContextClientActivity,
        active: Boolean,
    )
}

@Service
internal class ContextStateClientActivityListener(
    private val contextStateService: ContextStateApplicationService,
) : ClientActivityListener {
    override suspend fun onActivity(
        userId: User.Id,
        identity: RegisterClientSessionCommand,
        activity: ContextClientActivity,
        active: Boolean,
    ) {
        contextStateService.recordClientActivity(
            userId = userId,
            instanceId = identity.clientInstanceId.value,
            sessionId = identity.clientSessionId.value,
            platform = identity.platform.toContextPlatform(),
            activity = activity,
            active = active,
        )
    }
}

private fun ClientActivityKind.toContextActivity(): ContextClientActivity =
    when (this) {
        ClientActivityKind.WINDOW_FOCUSED -> ContextClientActivity.WINDOW_FOCUSED
        ClientActivityKind.USER_INTERACTION -> ContextClientActivity.USER_INTERACTION
    }

private fun com.gromozeka.remote.protocol.RemoteClientPlatform.toContextPlatform(): ContextClientPlatform =
    when (this) {
        com.gromozeka.remote.protocol.RemoteClientPlatform.DESKTOP -> ContextClientPlatform.DESKTOP
        com.gromozeka.remote.protocol.RemoteClientPlatform.ANDROID -> ContextClientPlatform.ANDROID
        com.gromozeka.remote.protocol.RemoteClientPlatform.IOS -> ContextClientPlatform.IOS
        com.gromozeka.remote.protocol.RemoteClientPlatform.WEB_DESKTOP -> ContextClientPlatform.WEB_DESKTOP
        com.gromozeka.remote.protocol.RemoteClientPlatform.WEB_TOUCH -> ContextClientPlatform.WEB_TOUCH
    }
