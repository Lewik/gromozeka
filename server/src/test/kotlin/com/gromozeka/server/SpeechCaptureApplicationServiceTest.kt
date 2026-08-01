package com.gromozeka.server

import com.gromozeka.domain.model.SpeechAudioSource
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.UserProfile
import com.gromozeka.domain.model.WorkerAudioInput
import com.gromozeka.domain.model.WorkerPermission
import com.gromozeka.domain.model.WorkerResource
import com.gromozeka.domain.model.ai.AiCatalog
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.model.ai.AiExecutionTarget
import com.gromozeka.domain.service.AiConfigurationProvider
import com.gromozeka.domain.service.ConversationRuntimeCapability
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import com.gromozeka.domain.service.ConversationRuntimeWorkerSessionId
import com.gromozeka.domain.service.ConversationRuntimeWorkerTargetResolver
import com.gromozeka.domain.service.SettingsProvider
import com.gromozeka.domain.service.WorkerAccessService
import com.gromozeka.domain.service.WorkerAudioCaptureClient
import com.gromozeka.domain.service.WorkerAudioCaptureRequest
import com.gromozeka.domain.service.WorkerAudioCaptureResult
import com.gromozeka.domain.service.WorkerCatalogEntry
import com.gromozeka.domain.service.WorkerCatalogService
import com.gromozeka.domain.service.WorkerEnvironmentProfile
import com.gromozeka.domain.service.WorkerNativeShell
import com.gromozeka.domain.service.WorkerOperatingSystem
import com.gromozeka.infrastructure.ai.openai.SttService
import com.gromozeka.remote.protocol.StartSpeechCaptureRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import org.mockito.Mockito
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SpeechCaptureApplicationServiceTest {
    @Test
    fun `direct Claude microphone does not depend on Java audio discovery and remains connection owned`() = runBlocking {
        val workerId = ConversationRuntimeWorkerId("voice-worker")
        val connection = claudeConnection(workerId)
        val client = RecordingAudioCaptureClient()
        val user = user("user-1")
        val service = service(
            connection = connection,
            sourceWorkerId = workerId,
            workers = listOf(
                worker(
                    workerId = workerId,
                    os = WorkerOperatingSystem.Family.WINDOWS,
                    audioInputs = emptyList(),
                )
            ),
            client = client,
            user = user,
        )
        val owner = SpeechCaptureSessionOwner(user.id, "client-1")

        service.start(owner, user, StartSpeechCaptureRequest("capture-1"))

        assertIs<WorkerAudioCaptureRequest.Command.StartClaudeCodeMicrophone>(
            client.requests.single().command
        )
        assertFailsWith<IllegalStateException> {
            service.stop(SpeechCaptureSessionOwner(user.id, "client-2"), "capture-1")
        }
        assertEquals("recognized speech", service.stop(owner, "capture-1").text)
    }

    @Test
    fun `availability prewarms direct Claude microphone on its exact Worker`() = runBlocking {
        val workerId = ConversationRuntimeWorkerId("voice-worker")
        val connection = claudeConnection(workerId)
        val client = RecordingAudioCaptureClient()
        val user = user("user-1")
        val service = service(
            connection = connection,
            sourceWorkerId = workerId,
            workers = listOf(worker(workerId, WorkerOperatingSystem.Family.MACOS)),
            client = client,
            user = user,
        )

        val availability = service.availability(user)

        assertTrue(availability.available)
        val request = client.requests.single()
        assertEquals(workerId, request.target.workerId)
        val command = assertIs<WorkerAudioCaptureRequest.Command.PrepareClaudeCodeMicrophone>(
            request.command
        )
        assertEquals(connection, command.connection)
    }

    @Test
    fun `cancel before start prevents a late Worker microphone from opening`() = runBlocking {
        val workerId = ConversationRuntimeWorkerId("voice-worker")
        val connection = claudeConnection(workerId)
        val client = RecordingAudioCaptureClient()
        val user = user("user-1")
        val service = service(
            connection = connection,
            sourceWorkerId = workerId,
            workers = listOf(worker(workerId, WorkerOperatingSystem.Family.WINDOWS)),
            client = client,
            user = user,
        )
        val owner = SpeechCaptureSessionOwner(user.id, "client-1")

        assertTrue(service.cancel(owner, "capture-1"))
        val error = assertFailsWith<IllegalStateException> {
            service.start(owner, user, StartSpeechCaptureRequest("capture-1"))
        }

        assertTrue(error.message.orEmpty().contains("cancelled before start"))
        assertTrue(client.requests.isEmpty())
    }

    @Test
    fun `cancel while Worker microphone is opening prevents a started response`() = runBlocking {
        val workerId = ConversationRuntimeWorkerId("voice-worker")
        val connection = claudeConnection(workerId)
        val client = BlockingStartAudioCaptureClient()
        val user = user("user-1")
        val service = service(
            connection = connection,
            sourceWorkerId = workerId,
            workers = listOf(worker(workerId, WorkerOperatingSystem.Family.WINDOWS)),
            client = client,
            user = user,
        )
        val owner = SpeechCaptureSessionOwner(user.id, "client-1")

        val start = async {
            runCatching { service.start(owner, user, StartSpeechCaptureRequest("capture-1")) }
        }
        client.startReceived.await()
        assertTrue(service.cancel(owner, "capture-1"))
        client.allowStart.complete(Unit)

        val startResult = start.await()
        assertTrue(startResult.isFailure)
        assertTrue(startResult.exceptionOrNull()?.message.orEmpty().contains("cancelled while starting"))
        assertEquals(
            listOf(
                WorkerAudioCaptureRequest.Command.StartClaudeCodeMicrophone::class,
                WorkerAudioCaptureRequest.Command.Cancel::class,
            ),
            client.requests.map { it.command::class },
        )
    }

    @Test
    fun `forwarding audio to Claude Code rejects a Windows target before recording starts`() = runBlocking {
        val sourceWorkerId = ConversationRuntimeWorkerId("source-worker")
        val targetWorkerId = ConversationRuntimeWorkerId("claude-worker")
        val client = RecordingAudioCaptureClient()
        val user = user("user-1")
        val service = service(
            connection = claudeConnection(targetWorkerId),
            sourceWorkerId = sourceWorkerId,
            workers = listOf(
                worker(sourceWorkerId, WorkerOperatingSystem.Family.WINDOWS),
                worker(targetWorkerId, WorkerOperatingSystem.Family.WINDOWS),
            ),
            client = client,
            user = user,
        )

        val availability = service.availability(user)

        assertFalse(availability.available)
        assertTrue(availability.unavailableReason.orEmpty().contains("Linux"))
        assertTrue(client.requests.isEmpty())
    }

    @Test
    fun `forwarding audio to Claude Code reports missing Linux audio utilities`() = runBlocking {
        val sourceWorkerId = ConversationRuntimeWorkerId("source-worker")
        val targetWorkerId = ConversationRuntimeWorkerId("claude-worker")
        val user = user("user-1")
        val service = service(
            connection = claudeConnection(targetWorkerId),
            sourceWorkerId = sourceWorkerId,
            workers = listOf(
                worker(sourceWorkerId, WorkerOperatingSystem.Family.LINUX),
                worker(
                    workerId = targetWorkerId,
                    os = WorkerOperatingSystem.Family.LINUX,
                    availableExecutables = listOf("claude", "pactl"),
                ),
            ),
            client = RecordingAudioCaptureClient(),
            user = user,
        )

        val availability = service.availability(user)

        assertFalse(availability.available)
        assertTrue(availability.unavailableReason.orEmpty().contains("paplay"))
    }

    @Test
    fun `direct Claude microphone reports a missing Claude executable`() = runBlocking {
        val workerId = ConversationRuntimeWorkerId("voice-worker")
        val user = user("user-1")
        val service = service(
            connection = claudeConnection(workerId),
            sourceWorkerId = workerId,
            workers = listOf(
                worker(
                    workerId = workerId,
                    os = WorkerOperatingSystem.Family.MACOS,
                    availableExecutables = emptyList(),
                )
            ),
            client = RecordingAudioCaptureClient(),
            user = user,
        )

        val availability = service.availability(user)

        assertFalse(availability.available)
        assertTrue(availability.unavailableReason.orEmpty().contains("Claude Code executable"))
    }

    @Test
    fun `client audio upload is rejected when a Worker source is selected`() = runBlocking {
        val workerId = ConversationRuntimeWorkerId("voice-worker")
        val user = user("user-1")
        val service = service(
            connection = claudeConnection(workerId),
            sourceWorkerId = workerId,
            workers = listOf(worker(workerId, WorkerOperatingSystem.Family.LINUX)),
            client = RecordingAudioCaptureClient(),
            user = user,
        )

        val error = assertFailsWith<IllegalArgumentException> {
            service.requireClientAudioRoute(user)
        }

        assertTrue(error.message.orEmpty().contains("Worker audio source"))
    }

    private fun service(
        connection: AiConnection.ClaudeCode,
        sourceWorkerId: ConversationRuntimeWorkerId,
        workers: List<WorkerCatalogEntry>,
        client: WorkerAudioCaptureClient,
        user: User,
    ): SpeechCaptureApplicationService {
        val settingsProvider = mock<SettingsProvider>()
        val profile = UserProfile(
            speechSettings = UserProfile.SpeechSettings(
                speechToText = UserProfile.SpeechSettings.SpeechToText(
                    enabled = true,
                    engine = UserProfile.SpeechSettings.SpeechToText.Engine.CLAUDE_CODE,
                    claudeCodeConnectionId = connection.id,
                    audioSource = SpeechAudioSource.WorkerInput(
                        sourceWorkerId,
                        WorkerAudioInput.SystemDefault.id,
                    ),
                )
            )
        )
        Mockito.`when`(settingsProvider.userProfile).thenReturn(profile)

        val catalog = mock<AiCatalog>()
        Mockito.`when`(catalog.connections).thenReturn(listOf(connection))
        val configurationProvider = mock<AiConfigurationProvider>()
        Mockito.`when`(configurationProvider.catalog).thenReturn(catalog)

        val accessService = mock<WorkerAccessService>()
        runBlocking {
            workers.forEach { entry ->
                Mockito.`when`(
                    accessService.requirePermission(user, entry.workerId, WorkerPermission.USE)
                ).thenReturn(mock<WorkerResource>())
            }
        }

        val identities = workers.associate { entry ->
            entry.workerId to ConversationRuntimeWorkerIdentity(
                entry.workerId,
                ConversationRuntimeWorkerSessionId("session-${entry.workerId.value}"),
            )
        }
        val targetResolver = mock<ConversationRuntimeWorkerTargetResolver>()
        runBlocking {
            workers.forEach { entry ->
                ConversationRuntimeCapability.entries.forEach { capability ->
                    Mockito.`when`(targetResolver.requireOnline(entry.workerId, capability))
                        .thenReturn(identities.getValue(entry.workerId))
                }
            }
        }

        return SpeechCaptureApplicationService(
            settingsProvider = settingsProvider,
            aiConfigurationProvider = configurationProvider,
            workerAccessService = accessService,
            workerCatalogService = object : WorkerCatalogService {
                override suspend fun listWorkers(): List<WorkerCatalogEntry> = workers
            },
            workerTargetResolver = targetResolver,
            workerAudioCaptureClient = client,
            sttService = mock<SttService>(),
        )
    }

    private fun claudeConnection(workerId: ConversationRuntimeWorkerId) = AiConnection.ClaudeCode(
        id = AiConnection.Id("claude-code"),
        displayName = "Claude Code",
        voiceTranscriptionEnabled = true,
        executionTarget = AiExecutionTarget.Worker(workerId.value),
    )

    private fun worker(
        workerId: ConversationRuntimeWorkerId,
        os: WorkerOperatingSystem.Family,
        audioInputs: List<WorkerAudioInput> = listOf(WorkerAudioInput.SystemDefault),
        availableExecutables: List<String> = listOf("claude", "pactl", "paplay"),
    ): WorkerCatalogEntry {
        val now = Instant.parse("2026-07-31T00:00:00Z")
        return WorkerCatalogEntry(
            workerId = workerId,
            status = WorkerCatalogEntry.Status.ONLINE,
            version = "test",
            startedAt = now,
            lastHeartbeatAt = now,
            environmentProfile = WorkerEnvironmentProfile(
                observedAt = now,
                operatingSystem = WorkerOperatingSystem(os, os.name, "test"),
                architecture = "test",
                nativeShell = WorkerNativeShell(WorkerNativeShell.Kind.POSIX_SH, "sh"),
                timezoneId = "UTC",
                localeTag = "en",
                logicalProcessorCount = 1,
                totalMemoryBytes = null,
                availableExecutables = availableExecutables,
                audioInputs = audioInputs,
            ),
        )
    }

    private fun user(id: String): User {
        val now = Instant.parse("2026-07-31T00:00:00Z")
        return User(
            id = User.Id(id),
            username = id,
            displayName = id,
            status = User.Status.ACTIVE,
            createdAt = now,
            updatedAt = now,
        )
    }

    private class RecordingAudioCaptureClient : WorkerAudioCaptureClient {
        val requests = mutableListOf<WorkerAudioCaptureRequest>()

        override suspend fun execute(request: WorkerAudioCaptureRequest): WorkerAudioCaptureResult {
            requests += request
            return when (request.command) {
                is WorkerAudioCaptureRequest.Command.PrepareClaudeCodeMicrophone ->
                    WorkerAudioCaptureResult(WorkerAudioCaptureResult.Status.PREPARED)
                is WorkerAudioCaptureRequest.Command.StartAudio,
                is WorkerAudioCaptureRequest.Command.StartClaudeCodeMicrophone,
                -> WorkerAudioCaptureResult(WorkerAudioCaptureResult.Status.STARTED)
                is WorkerAudioCaptureRequest.Command.Stop -> WorkerAudioCaptureResult(
                    status = WorkerAudioCaptureResult.Status.TRANSCRIBED,
                    transcript = "recognized speech",
                )
                is WorkerAudioCaptureRequest.Command.Cancel ->
                    WorkerAudioCaptureResult(WorkerAudioCaptureResult.Status.CANCELLED)
            }
        }
    }

    private class BlockingStartAudioCaptureClient : WorkerAudioCaptureClient {
        val startReceived = CompletableDeferred<Unit>()
        val allowStart = CompletableDeferred<Unit>()
        val requests = mutableListOf<WorkerAudioCaptureRequest>()

        override suspend fun execute(request: WorkerAudioCaptureRequest): WorkerAudioCaptureResult {
            requests += request
            return when (request.command) {
                is WorkerAudioCaptureRequest.Command.StartClaudeCodeMicrophone -> {
                    startReceived.complete(Unit)
                    allowStart.await()
                    WorkerAudioCaptureResult(WorkerAudioCaptureResult.Status.STARTED)
                }
                is WorkerAudioCaptureRequest.Command.Cancel ->
                    WorkerAudioCaptureResult(WorkerAudioCaptureResult.Status.CANCELLED)
                else -> error("Unexpected command: ${request.command}")
            }
        }
    }

    private inline fun <reified T> mock(): T = Mockito.mock(T::class.java)
}
