package com.gromozeka.server.telegram

import com.gromozeka.application.service.ConversationArtifactApplicationService
import com.gromozeka.application.service.NamedSecretApplicationService
import com.gromozeka.domain.model.*
import com.gromozeka.domain.repository.IdentityRepository
import com.gromozeka.domain.repository.TelegramConnectionRepository
import com.gromozeka.domain.service.*
import com.gromozeka.server.GromozekaRemoteAuthorization
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.springframework.core.env.Environment
import org.springframework.stereotype.Service
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

@Service
class TelegramConnectionApiFactory(
    private val secrets: NamedSecretApplicationService,
    private val environment: Environment,
) {
    val serverEnabled: Boolean get() = environment.getProperty("gromozeka.telegram.enabled", Boolean::class.java, false)

    suspend fun create(connection: TelegramConnection): TelegramHttpApi = create(connection.ownerUserId, connection.tokenSecretName)

    suspend fun create(userId: User.Id, secretName: String): TelegramHttpApi {
        check(serverEnabled) { "Telegram is disabled in this Server deployment" }
        val token = secrets.resolve(userId, setOf(secretName)).getValue(secretName).trim()
        require(token.matches(Regex("[0-9]+:[A-Za-z0-9_-]+"))) { "The selected secret is not a Telegram bot token" }
        return TelegramHttpApi(token)
    }
}

@Service
class TelegramManagementApplicationService(
    private val repository: TelegramConnectionRepository,
    private val apiFactory: TelegramConnectionApiFactory,
    private val authorization: GromozekaRemoteAuthorization,
    private val identities: IdentityRepository,
    private val artifacts: ConversationArtifactApplicationService,
    private val stateChanges: DeclarativeStateChangePublisher,
    private val projectAccess: ProjectAccessService,
    private val conversations: ConversationDomainService,
    private val agents: AgentDomainService,
) : TelegramManagementService {
    override suspend fun snapshot(actor: User): TelegramManagementSnapshot {
        requireOwner(actor)
        val targets = buildList {
            for (project in projectAccess.findAll(actor.id)) {
                if (!projectAccess.can(actor.id, project.id, ProjectPermission.WRITE)) continue
                for (conversation in conversations.findByProject(project.id)) {
                    if (Conversation.Participant.User(actor.id) !in conversation.participants) continue
                    val connected = conversation.participants.filterIsInstance<Conversation.Participant.Agent>().mapNotNull {
                        agents.findById(it.agentDefinitionId)?.let { agent -> TelegramAgentTarget(agent.id, agent.name) }
                    }
                    add(TelegramConversationTarget(conversation.id, project.name, conversation.displayName, connected, conversation.externalChannel))
                }
            }
        }
        return TelegramManagementSnapshot(apiFactory.serverEnabled, repository.list().filter { it.ownerUserId == actor.id }, targets)
    }

    override suspend fun probe(actor: User, tokenSecretName: String): TelegramBotProfile {
        requireOwner(actor)
        return readProfile(apiFactory.create(actor.id, tokenSecretName))
    }

    override suspend fun save(actor: User, connection: TelegramConnection, expectedRevision: Long): TelegramConnection {
        requireOwner(actor)
        require(connection.ownerUserId == actor.id) { "Telegram connection belongs to another user" }
        val existing = repository.find(connection.id)
        require(existing == null || existing.ownerUserId == actor.id)
        require(connection.revision == expectedRevision)
        if (connection.enabled) {
            val profile = readProfile(apiFactory.create(connection))
            require(profile.botId == connection.botId && profile.username == connection.botUsername) { "Bot identity does not match the selected token" }
            require(profile.canReadAllGroupMessages) { "Disable the bot privacy mode in BotFather and re-add it to the group" }
        }
        val projects = linkedSetOf<Project.Id>()
        for (binding in connection.bindings) {
            if (!connection.enabled && binding in existing?.bindings.orEmpty()) continue
            val conversation = authorization.requireConversation(actor, binding.conversationId, ProjectPermission.WRITE)
            require(conversation.externalChannel?.let { it.connectionId != connection.id } != true) {
                "Conversation is already connected to another external channel"
            }
            projects += conversation.projectId
            for (route in binding.routes) {
                require(Conversation.Participant.Agent(route.agentId) in conversation.participants) { "Connect the selected agent to the conversation first" }
                authorization.authorize(actor, com.gromozeka.remote.protocol.FindAgentRequest(route.agentId))
            }
        }
        for (binding in existing?.bindings.orEmpty()) {
            conversations.findById(binding.conversationId)?.let { projects += it.projectId }
        }
        val triggerCutoff = if (connection.enabled) kotlin.time.Clock.System.now().epochSeconds
            else existing?.acceptTriggersAfterEpochSeconds ?: Long.MAX_VALUE
        val activationRevision = if (connection.enabled) expectedRevision + 1 else existing?.activationRevision ?: 0
        val saved = repository.save(connection.copy(acceptTriggersAfterEpochSeconds = triggerCutoff, activationRevision = activationRevision), expectedRevision)
        projects.forEach { stateChanges.publish(DeclarativeStateKey.projectConversations(it)) }
        return saved
    }

    override suspend fun profile(actor: User, connectionId: String): TelegramBotProfile =
        readProfile(apiFactory.create(requireConnection(actor, connectionId)))

    override suspend fun updateProfile(actor: User, connectionId: String, update: TelegramProfileUpdate): TelegramBotProfile {
        val connection = requireConnection(actor, connectionId)
        val avatar = update.avatarArtifactId?.let { id ->
            val artifact = artifacts.find(id) ?: error("Avatar artifact does not exist")
            authorization.requireConversation(actor, artifact.conversationId, ProjectPermission.READ)
            require(artifact.mediaType.startsWith("image/")) { "Avatar must be an image" }
            prepareAvatar(artifacts.read(id))
        }
        val api = apiFactory.create(connection)
        check(api.call("getMe").jsonObject.long("id") == connection.botId) { "Bot identity changed" }
        api.call("setMyName", buildJsonObject { put("name", update.name) })
        api.call("setMyDescription", buildJsonObject { put("description", update.description) })
        api.call("setMyShortDescription", buildJsonObject { put("short_description", update.shortDescription) })
        if (avatar != null) api.setProfilePhoto(avatar)
        return readProfile(api)
    }

    private suspend fun requireConnection(actor: User, id: String): TelegramConnection {
        requireOwner(actor)
        return checkNotNull(repository.find(id)) { "Telegram connection not found" }.also {
            require(it.ownerUserId == actor.id) { "Telegram connection belongs to another user" }
        }
    }

    private suspend fun requireOwner(actor: User) {
        val current = identities.findUserById(actor.id)
        require(current?.canLogin == true && current.role == User.Role.OWNER) { "Server owner permission is required" }
    }

    private suspend fun readProfile(api: TelegramHttpApi): TelegramBotProfile {
        val me = api.call("getMe").jsonObject
        return TelegramBotProfile(
            botId = checkNotNull(me.long("id")), username = checkNotNull(me.string("username")),
            name = api.call("getMyName").jsonObject.string("name").orEmpty(),
            description = api.call("getMyDescription").jsonObject.string("description").orEmpty(),
            shortDescription = api.call("getMyShortDescription").jsonObject.string("short_description").orEmpty(),
            canReadAllGroupMessages = me.boolean("can_read_all_group_messages"),
        )
    }

    private suspend fun prepareAvatar(bytes: ByteArray): ByteArray = withContext(Dispatchers.IO) {
        ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { stream ->
            val readers = ImageIO.getImageReaders(stream)
            require(readers.hasNext()) { "Unsupported avatar image" }
            val reader = readers.next()
            try {
                reader.input = stream
                require(reader.getWidth(0) in 1..8192 && reader.getHeight(0) in 1..8192) { "Avatar dimensions are too large" }
                val image = reader.read(0)
                val result = BufferedImage(640, 640, BufferedImage.TYPE_INT_RGB)
                val graphics = result.createGraphics()
                try {
                    graphics.color = Color(32, 33, 36)
                    graphics.fillRect(0, 0, 640, 640)
                    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                    val scale = minOf(640.0 / image.width, 640.0 / image.height)
                    val width = (image.width * scale).toInt().coerceAtLeast(1)
                    val height = (image.height * scale).toInt().coerceAtLeast(1)
                    graphics.drawImage(image, (640 - width) / 2, (640 - height) / 2, width, height, null)
                } finally { graphics.dispose() }
                ByteArrayOutputStream().use { output -> check(ImageIO.write(result, "jpg", output)); output.toByteArray() }
            } finally { reader.dispose() }
        }
    }
}

@Service
class TelegramArtifactContentReader(
    private val connections: TelegramConnectionRepository,
    private val apiFactory: TelegramConnectionApiFactory,
) : ExternalArtifactContentReader {
    override fun supports(source: Artifact.ContentSource): Boolean = source is Artifact.ContentSource.Telegram

    override suspend fun read(artifact: Artifact, maximumBytes: Int): ByteArray {
        val source = artifact.source as Artifact.ContentSource.Telegram
        val connection = connections.find(source.connectionId)
            ?: throw ArtifactContentUnavailableException("Telegram connection no longer exists")
        if (!apiFactory.serverEnabled || !connection.enabled) throw ArtifactContentUnavailableException("Telegram connection is disabled")
        if ((artifact.sizeBytes ?: 0) > 20 * 1024 * 1024) throw ArtifactContentUnavailableException("Telegram cloud downloads are limited to 20 MB")
        try {
            return apiFactory.create(connection).download(source.fileId, minOf(maximumBytes, 20 * 1024 * 1024))
        } catch (_: TelegramApiFailure) {
            throw ArtifactContentUnavailableException("Telegram could not provide this file")
        } catch (_: TelegramDeliveryUncertain) {
            throw ArtifactContentUnavailableException("Telegram is temporarily unavailable")
        }
    }
}
