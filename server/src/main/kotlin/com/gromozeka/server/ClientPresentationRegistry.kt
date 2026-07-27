package com.gromozeka.server

import com.gromozeka.domain.model.Conversation
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
    private val sessionsById = mutableMapOf<ClientSessionId, RegisteredClientSession>()
    private val sessionIdsByConnection = mutableMapOf<String, ClientSessionId>()
    private val presentedMessageIds = LinkedHashSet<Conversation.Message.Id>()
    private var activeSessionId: ClientSessionId? = null

    suspend fun register(
        connectionId: String,
        command: RegisterClientSessionCommand,
        encoding: RemoteProtocolEncoding,
        send: ClientPresentationSend,
    ) {
        require(command.clientInstanceId.value.isNotBlank()) { "Client instance ID must not be blank" }
        require(command.clientSessionId.value.isNotBlank()) { "Client session ID must not be blank" }

        mutex.withLock {
            sessionIdsByConnection.put(connectionId, command.clientSessionId)
                ?.takeIf { it != command.clientSessionId }
                ?.let { previousSessionId ->
                    sessionsById[previousSessionId]
                        ?.takeIf { it.connectionId == connectionId }
                        ?.let { sessionsById.remove(previousSessionId) }
                }
            sessionsById.put(
                command.clientSessionId,
                RegisteredClientSession(
                    connectionId = connectionId,
                    identity = command,
                    encoding = encoding,
                    send = send,
                ),
            )?.let { previous ->
                sessionIdsByConnection.remove(previous.connectionId)
            }
            sessionIdsByConnection[connectionId] = command.clientSessionId
        }
        log.info {
            "Client presentation session registered: instance=${command.clientInstanceId.value} " +
                "session=${command.clientSessionId.value} platform=${command.platform}"
        }
    }

    suspend fun updateEncoding(connectionId: String, encoding: RemoteProtocolEncoding) {
        mutex.withLock {
            val sessionId = sessionIdsByConnection[connectionId] ?: return@withLock
            sessionsById[sessionId]?.encoding = encoding
        }
    }

    suspend fun requireRegistered(connectionId: String) {
        check(mutex.withLock { connectionId in sessionIdsByConnection }) {
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
            val sessionId = sessionIdsByConnection[connectionId]
                ?: error("Client session must be registered before reporting activity")
            if (activeSessionId == sessionId) {
                return@withLock null
            }

            val previous = activeSessionId?.let(sessionsById::get)
            activeSessionId = sessionId
            Activation(
                current = sessionsById.getValue(sessionId),
                previous = previous,
            )
        } ?: return

        log.info {
            "Active interaction client changed: instance=${activation.current.identity.clientInstanceId.value} " +
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
            val sessionId = sessionIdsByConnection.remove(connectionId) ?: return@withLock null
            sessionsById[sessionId]
                ?.takeIf { it.connectionId == connectionId }
                ?.also { sessionsById.remove(sessionId) }
        } ?: return

        log.info {
            "Client presentation session disconnected: " +
                "instance=${disconnected.identity.clientInstanceId.value} " +
                "session=${disconnected.identity.clientSessionId.value}"
        }
    }

    suspend fun present(message: Conversation.Message): Boolean =
        deliveryMutex.withLock {
            presentToActiveClient(message)
        }

    private suspend fun presentToActiveClient(message: Conversation.Message): Boolean {
        val speech = message.assistantSpeech() ?: return false
        val claim = mutex.withLock {
            val firstPresentation = presentedMessageIds.add(message.id)
            trimPresentedMessageIds()
            if (!firstPresentation) {
                PresentationClaim.Duplicate
            } else {
                activeSessionId
                    ?.let(sessionsById::get)
                    ?.let(PresentationClaim::Target)
                    ?: PresentationClaim.NoActiveClient
            }
        }

        val target = when (claim) {
            PresentationClaim.Duplicate -> return false
            PresentationClaim.NoActiveClient -> {
                log.info { "Auto TTS has no active client: message=${message.id.value}" }
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
                "Auto TTS routed: message=${message.id.value} " +
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

    private fun trimPresentedMessageIds() {
        while (presentedMessageIds.size > MAX_PRESENTED_MESSAGE_IDS) {
            presentedMessageIds.remove(presentedMessageIds.first())
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
        val identity: RegisterClientSessionCommand,
        var encoding: RemoteProtocolEncoding,
        val send: ClientPresentationSend,
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
        const val MAX_PRESENTED_MESSAGE_IDS = 10_000
    }
}
