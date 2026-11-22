package com.gromozeka.server

import com.gromozeka.domain.model.SpeechAudioFormat
import com.gromozeka.domain.model.SpeechAudioSource
import com.gromozeka.domain.model.SpeechAvailabilityFailure
import com.gromozeka.domain.model.User
import com.gromozeka.domain.model.UserProfile
import com.gromozeka.domain.model.WorkerAudioInput
import com.gromozeka.domain.model.WorkerPermission
import com.gromozeka.domain.model.WorkerResource
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
import com.gromozeka.remote.protocol.StartSpeechCaptureRequest
import com.gromozeka.shared.audio.SpeechPcmWav
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.mockito.Mockito
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

class SpeechCaptureApplicationServiceTest {
    @Test
    fun `Worker capture returns immutable audio before transcription`() = runBlocking {
        val workerId = ConversationRuntimeWorkerId("voice-worker")
        val client = RecordingAudioCaptureClient()
        val user = user("user-1")
        val service = service(workerId, listOf(worker(workerId)), client, user)
        val owner = SpeechCaptureSessionOwner(user.id, "client-1")

        service.start(owner, user, StartSpeechCaptureRequest("capture-1"))

        val start = assertIs<WorkerAudioCaptureRequest.Command.StartAudio>(client.requests.single().command)
        assertEquals(WorkerAudioInput.SystemDefault.id, start.inputId)
        assertFailsWith<IllegalStateException> {
            service.stop(SpeechCaptureSessionOwner(user.id, "client-2"), "capture-1")
        }

        val stopped = service.stop(owner, "capture-1")
        assertEquals("capture-1", stopped.recording.sessionId)
        assertEquals(SpeechAudioFormat.WAV_PCM_S16LE_MONO_16_KHZ, stopped.recording.format)
        assertContentEquals(client.audio, stopped.recording.chunks.single().data)
        assertIs<WorkerAudioCaptureRequest.Command.Stop>(client.requests.last().command)
    }

    @Test
    fun `failed stop keeps the Worker session available for cancellation`() = runBlocking {
        val workerId = ConversationRuntimeWorkerId("voice-worker")
        val client = RecordingAudioCaptureClient().apply {
            stopFailure = IllegalStateException("synthetic stop failure")
        }
        val user = user("user-1")
        val service = service(workerId, listOf(worker(workerId)), client, user)
        val owner = SpeechCaptureSessionOwner(user.id, "client-1")

        service.start(owner, user, StartSpeechCaptureRequest("capture-1"))
        assertFailsWith<IllegalStateException> { service.stop(owner, "capture-1") }
        assertTrue(service.cancel(owner, "capture-1"))

        assertEquals(
            listOf(
                WorkerAudioCaptureRequest.Command.StartAudio::class,
                WorkerAudioCaptureRequest.Command.Stop::class,
                WorkerAudioCaptureRequest.Command.Cancel::class,
            ),
            client.requests.map { it.command::class },
        )
    }

    @Test
    fun `cancel before start prevents a late Worker microphone from opening`() = runBlocking {
        val workerId = ConversationRuntimeWorkerId("voice-worker")
        val client = RecordingAudioCaptureClient()
        val user = user("user-1")
        val service = service(workerId, listOf(worker(workerId)), client, user)
        val owner = SpeechCaptureSessionOwner(user.id, "client-1")

        assertTrue(service.cancel(owner, "capture-1"))
        val error = assertFailsWith<IllegalStateException> {
            service.start(owner, user, StartSpeechCaptureRequest("capture-1"))
        }

        assertTrue(error.message.orEmpty().contains("cancelled before start"))
        assertTrue(client.requests.isEmpty())
    }

    @Test
    fun `failed start cleanup remains cancellable when Worker cancellation fails`() = runBlocking {
        val workerId = ConversationRuntimeWorkerId("voice-worker")
        val client = FailingStartCleanupAudioCaptureClient()
        val user = user("user-1")
        val service = service(workerId, listOf(worker(workerId)), client, user)
        val owner = SpeechCaptureSessionOwner(user.id, "client-1")

        assertFailsWith<IllegalStateException> {
            service.start(owner, user, StartSpeechCaptureRequest("capture-1"))
        }
        assertTrue(service.cancel(owner, "capture-1"))

        assertEquals(
            listOf(
                WorkerAudioCaptureRequest.Command.StartAudio::class,
                WorkerAudioCaptureRequest.Command.Cancel::class,
                WorkerAudioCaptureRequest.Command.Cancel::class,
            ),
            client.requests.map { it.command::class },
        )
    }

    @Test
    fun `cancel while Worker microphone is opening prevents a started response`() = runBlocking {
        val workerId = ConversationRuntimeWorkerId("voice-worker")
        val client = BlockingStartAudioCaptureClient()
        val user = user("user-1")
        val service = service(workerId, listOf(worker(workerId)), client, user)
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
                WorkerAudioCaptureRequest.Command.StartAudio::class,
                WorkerAudioCaptureRequest.Command.Cancel::class,
            ),
            client.requests.map { it.command::class },
        )
    }

    @Test
    fun `availability reports a missing selected Worker input`() = runBlocking {
        val workerId = ConversationRuntimeWorkerId("voice-worker")
        val user = user("user-1")
        val service = service(
            workerId,
            listOf(worker(workerId, audioInputs = emptyList())),
            RecordingAudioCaptureClient(),
            user,
        )

        val availability = service.availability(user)

        assertEquals(false, availability.available)
        assertEquals(SpeechAvailabilityFailure.Code.AUDIO_INPUT_UNAVAILABLE, availability.unavailableReason?.code)
    }

    @Test
    fun `captured audio can be transcribed while a Worker source remains selected`() = runBlocking {
        val workerId = ConversationRuntimeWorkerId("voice-worker")
        val user = user("user-1")
        val service = service(workerId, listOf(worker(workerId)), RecordingAudioCaptureClient(), user)

        service.requireTranscriptionRoute(user)
    }

    private fun service(
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
                    engine = UserProfile.SpeechSettings.SpeechToText.Engine.LOCAL_WHISPER,
                    audioSource = SpeechAudioSource.WorkerInput(
                        sourceWorkerId,
                        WorkerAudioInput.SystemDefault.id,
                    ),
                    localWhisper = UserProfile.SpeechSettings.SpeechToText.LocalWhisper(
                        executionTarget = AiExecutionTarget.Server,
                    ),
                )
            )
        )
        Mockito.`when`(settingsProvider.userProfile).thenReturn(profile)

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
            aiConfigurationProvider = mock<AiConfigurationProvider>(),
            workerAccessService = accessService,
            workerCatalogService = object : WorkerCatalogService {
                override suspend fun listWorkers(): List<WorkerCatalogEntry> = workers
            },
            workerTargetResolver = targetResolver,
            workerAudioCaptureClient = client,
        )
    }

    private fun worker(
        workerId: ConversationRuntimeWorkerId,
        audioInputs: List<WorkerAudioInput> = listOf(WorkerAudioInput.SystemDefault),
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
                operatingSystem = WorkerOperatingSystem(WorkerOperatingSystem.Family.LINUX, "Linux", "test"),
                architecture = "test",
                nativeShell = WorkerNativeShell(WorkerNativeShell.Kind.POSIX_SH, "sh"),
                timezoneId = "UTC",
                localeTag = "en",
                logicalProcessorCount = 1,
                totalMemoryBytes = null,
                availableExecutables = emptyList(),
                audioInputs = audioInputs,
            ),
        )
    }

    private fun user(id: String): User {
        val now = Instant.parse("2026-07-31T00:00:00Z")
        return User(
            id = User.Id(id),
            identities = listOf(com.gromozeka.domain.model.UserIdentity.LocalLogin(id)),
            displayName = id,
            status = User.Status.ACTIVE,
            createdAt = now,
            updatedAt = now,
        )
    }

    private class RecordingAudioCaptureClient : WorkerAudioCaptureClient {
        val requests = mutableListOf<WorkerAudioCaptureRequest>()
        val audio = SpeechPcmWav.encode(ByteArray(640))
        var stopFailure: Throwable? = null

        override suspend fun execute(request: WorkerAudioCaptureRequest): WorkerAudioCaptureResult {
            requests += request
            return when (request.command) {
                is WorkerAudioCaptureRequest.Command.StartAudio ->
                    WorkerAudioCaptureResult(WorkerAudioCaptureResult.Status.STARTED)
                is WorkerAudioCaptureRequest.Command.Stop -> {
                    stopFailure?.let { throw it }
                    WorkerAudioCaptureResult(
                        status = WorkerAudioCaptureResult.Status.AUDIO_CAPTURED,
                        audioData = audio,
                        format = SpeechAudioFormat.WAV_PCM_S16LE_MONO_16_KHZ,
                    )
                }
                is WorkerAudioCaptureRequest.Command.Cancel ->
                    WorkerAudioCaptureResult(WorkerAudioCaptureResult.Status.CANCELLED)
            }
        }
    }

    private class FailingStartCleanupAudioCaptureClient : WorkerAudioCaptureClient {
        val requests = mutableListOf<WorkerAudioCaptureRequest>()
        private var cancelAttempts = 0

        override suspend fun execute(request: WorkerAudioCaptureRequest): WorkerAudioCaptureResult {
            requests += request
            return when (request.command) {
                is WorkerAudioCaptureRequest.Command.StartAudio ->
                    error("synthetic start failure")
                is WorkerAudioCaptureRequest.Command.Cancel -> {
                    cancelAttempts++
                    if (cancelAttempts == 1) error("synthetic cleanup failure")
                    WorkerAudioCaptureResult(WorkerAudioCaptureResult.Status.CANCELLED)
                }
                is WorkerAudioCaptureRequest.Command.Stop -> error("Unexpected stop")
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
                is WorkerAudioCaptureRequest.Command.StartAudio -> {
                    startReceived.complete(Unit)
                    allowStart.await()
                    WorkerAudioCaptureResult(WorkerAudioCaptureResult.Status.STARTED)
                }
                is WorkerAudioCaptureRequest.Command.Cancel ->
                    WorkerAudioCaptureResult(WorkerAudioCaptureResult.Status.CANCELLED)
                is WorkerAudioCaptureRequest.Command.Stop -> error("Unexpected stop")
            }
        }
    }

    private inline fun <reified T> mock(): T = Mockito.mock(T::class.java)
}
