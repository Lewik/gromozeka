package com.gromozeka.server

import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.model.User
import com.gromozeka.remote.protocol.ClientActivityKind
import com.gromozeka.remote.protocol.ClientSessionId
import com.gromozeka.remote.protocol.PlayMessageTtsDirective
import com.gromozeka.remote.protocol.RegisterClientSessionCommand
import com.gromozeka.remote.protocol.RemoteProtocolEncoding
import com.gromozeka.remote.protocol.ServerPayload
import com.gromozeka.remote.protocol.StopTtsDirective
import klog.KLoggers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.stereotype.Service

internal typealias ClientPresentationSend = suspend (ServerPayload, RemoteProtocolEncoding) -> Unit

@Service
class ClientPresentationRegistry {
    private val log = KLoggers.logger(this)
    private val mutex = Mutex()
    private val deliveryMutex = Mutex()
    private val sessionsByKey = mutableMapOf<ClientSessionKey, RegisteredClientSession>()
    private val sessionKeysByConnection = mutableMapOf<String, ClientSessionKey>()
    private val presentedMessageKeys = LinkedHashSet<PresentedMessageKey>()
    private val activeSessionKeysByUser = mutableMapOf<User.Id, ClientSessionKey>()

    suspend fun register(
        userId: User.Id,
        connectionId: String,
        command: RegisterClientSessionCommand,
        encoding: RemoteProtocolEncoding,
        send: ClientPresentationSend,
    ) {
        require(command.clientInstanceId.value.isNotBlank()) { "Client instance ID must not be blank" }
        require(command.clientSessionId.value.isNotBlank()) { "Client session ID must not be blank" }

        mutex.withLock {
            val sessionKey = ClientSessionKey(userId, command.clientSessionId)
            sessionKeysByConnection.put(connectionId, sessionKey)
                ?.takeIf { it != sessionKey }
                ?.let { previousSessionKey ->
                    sessionsByKey[previousSessionKey]
                        ?.takeIf { it.connectionId == connectionId }
                        ?.let { sessionsByKey.remove(previousSessionKey) }
                }
            sessionsByKey.put(
                sessionKey,
                RegisteredClientSession(
                    connectionId = connectionId,
                    userId = userId,
                    identity = command,
                    encoding = encoding,
                    send = send,
                ),
            )?.let { previous ->
                sessionKeysByConnection.remove(previous.connectionId)
            }
            sessionKeysByConnection[connectionId] = sessionKey
        }
        log.info {
            "Client presentation session registered: user=${userId.value} instance=${command.clientInstanceId.value} " +
                "session=${command.clientSessionId.value} platform=${command.platform}"
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
            activeSessionKeysByUser[sessionKey.userId] = sessionKey
            Activation(
                current = sessionsByKey.getValue(sessionKey),
                previous = previous,
            )
        } ?: return

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

    suspend fun disconnect(connectionId: String) {
        val disconnected = mutex.withLock {
            val sessionKey = sessionKeysByConnection.remove(connectionId) ?: return@withLock null
            sessionsByKey[sessionKey]
                ?.takeIf { it.connectionId == connectionId }
                ?.also { sessionsByKey.remove(sessionKey) }
        } ?: return

        log.info {
            "Client presentation session disconnected: user=${disconnected.userId.value} " +
                "instance=${disconnected.identity.clientInstanceId.value} " +
                "session=${disconnected.identity.clientSessionId.value}"
        }
    }

    suspend fun present(userId: User.Id, message: Conversation.Message): Boolean =
        deliveryMutex.withLock {
            presentToActiveClient(userId, message)
        }

    private suspend fun presentToActiveClient(userId: User.Id, message: Conversation.Message): Boolean {
        val speech = message.assistantSpeech() ?: return false
        val claim = mutex.withLock {
            val firstPresentation = presentedMessageKeys.add(PresentedMessageKey(userId, message.id))
            trimPresentedMessageKeys()
            if (!firstPresentation) {
                PresentationClaim.Duplicate
            } else {
                activeSessionKeysByUser[userId]
                    ?.let(sessionsByKey::get)
                    ?.let(PresentationClaim::Target)
                    ?: PresentationClaim.NoActiveClient
            }
        }

        val target = when (claim) {
            PresentationClaim.Duplicate -> return false
            PresentationClaim.NoActiveClient -> {
                log.info { "Auto TTS has no active client: user=${userId.value} message=${message.id.value}" }
                return false
            }
            is PresentationClaim.Target -> claim.session
        }

        return try {
            target.send(
                PlayMessageTtsDirective(
                    messageId = message.id,
                    text = speech.text,
                    tone = speech.tone,
                ),
                target.encoding,
            )
            log.info {
                "Auto TTS routed: user=${userId.value} message=${message.id.value} " +
                    "instance=${target.identity.clientInstanceId.value} " +
                    "session=${target.identity.clientSessionId.value}"
            }
            true
        } catch (error: Throwable) {
            log.warn(error) {
                "Failed to route auto TTS: message=${message.id.value} " +
                    "session=${target.identity.clientSessionId.value} error=${error.message}"
            }
            false
        }
    }

    private fun trimPresentedMessageKeys() {
        while (presentedMessageKeys.size > MAX_PRESENTED_MESSAGE_KEYS) {
            presentedMessageKeys.remove(presentedMessageKeys.first())
        }
    }

    private fun Conversation.Message.assistantSpeech(): AssistantSpeech? {
        if (role != Conversation.Message.Role.ASSISTANT) {
            return null
        }
        val structured = content
            .filterIsInstance<Conversation.Message.ContentItem.AssistantMessage>()
            .firstOrNull()
            ?.structured
            ?: return null
        val text = structured.ttsText?.trim()?.takeIf(String::isNotBlank) ?: return null
        return AssistantSpeech(text, structured.voiceTone.orEmpty())
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

    private data class PresentedMessageKey(
        val userId: User.Id,
        val messageId: Conversation.Message.Id,
    )

    private data class Activation(
        val current: RegisteredClientSession,
        val previous: RegisteredClientSession?,
    )

    private data class AssistantSpeech(
        val text: String,
        val tone: String,
    )

    private sealed interface PresentationClaim {
        data object Duplicate : PresentationClaim
        data object NoActiveClient : PresentationClaim
        data class Target(val session: RegisteredClientSession) : PresentationClaim
    }

    private companion object {
        const val MAX_PRESENTED_MESSAGE_KEYS = 10_000
    }
}
