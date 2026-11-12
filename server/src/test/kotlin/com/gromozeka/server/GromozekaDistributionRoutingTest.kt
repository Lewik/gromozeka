package com.gromozeka.server

import com.gromozeka.domain.model.mcp.BrowserUseMcpPreset
import com.gromozeka.remote.protocol.DistributionComponent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import com.gromozeka.application.service.ApplicationUpdateService
import com.gromozeka.application.service.SettingsService
import com.gromozeka.domain.model.*
import com.gromozeka.domain.repository.ApplicationUpdateRepository
import com.gromozeka.domain.repository.WorkerEnrollmentRepository
import com.gromozeka.domain.service.AuthenticationService
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.remote.protocol.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.mockito.Answers
import org.mockito.Mockito
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class GromozekaDistributionRoutingTest {
    @Test
    fun `private update delivery authenticates clients and workers and persists explicit state transitions`() = runBlocking {
        val directory = Files.createTempDirectory("application-updates").toFile()
        try {
            val version = GromozekaServerApplication::class.java.`package`.implementationVersion ?: "0.0.0-dev"
            val release = File(directory, "updates/$version").apply { mkdirs() }
            val bytes = byteArrayOf(0, 1, 2, -1)
            val file = File(release, "fixture.zip").apply { writeBytes(bytes) }
            val artifact = ApplicationUpdateArtifact(UpdateComponent.CLIENT, UpdatePlatform.WINDOWS_X64, version,
                file.name, bytes.size.toLong(), MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }, UpdatePackageFormat.WINDOWS_ZIP)
            val workerArtifact = artifact.copy(component = UpdateComponent.WORKER, fileName = "worker.zip")
            File(release, workerArtifact.fileName).writeBytes(bytes)
            File(release, "catalog.json").writeText(Json.encodeToString(ApplicationUpdateCatalog(version = version, artifacts = listOf(artifact, workerArtifact))))
            val settings = Mockito.mock(SettingsService::class.java) { call -> if (call.method.name == "getGromozekaHome") directory else Answers.RETURNS_DEFAULTS.answer(call) }
            val catalog = ApplicationUpdateCatalogFiles(settings)
            val rows = mutableMapOf<String, ApplicationInstallation>()
            val repository = object : ApplicationUpdateRepository {
                override suspend fun list() = rows.values.toList()
                override suspend fun find(id: String) = rows[id]
                override suspend fun modify(id: String, initial: ApplicationInstallation?, transform: (ApplicationInstallation) -> ApplicationInstallation) =
                    transform(requireNotNull(rows[id] ?: initial)).also { rows[id] = it }
            }
            val service = ApplicationUpdateService(repository, catalog)
            val user = testControlMcpCaller().user.copy(role = User.Role.MEMBER)
            val stranger = user.copy(id = User.Id("another-user"))
            val authentication = Mockito.mock(AuthenticationService::class.java) { call ->
                if (call.method.name == "authenticate" && call.arguments.first() == "update-session") AuthenticatedUser(user, UserSession.Id("update-session"))
                else Answers.RETURNS_DEFAULTS.answer(call)
            }
            val worker = WorkerResource(ConversationRuntimeWorkerId("update-worker"), "Test Worker", user.id,
                runtimeWideAccess = false, status = WorkerResource.Status.ACTIVE, createdAt = user.createdAt, updatedAt = user.createdAt)
            val enrollment = Mockito.mock(WorkerEnrollmentRepository::class.java) { call ->
                if (call.method.name.startsWith("authenticateGatewayCredential")) worker else Answers.RETURNS_DEFAULTS.answer(call)
            }
            val workers = WorkerGatewayAuthenticationService(enrollment)
            val controller = ApplicationUpdateRouting(service, catalog, authentication, workers, ServerHttpSettings())
            SpringHttpTestFixture(controller, authentication = authentication, workers = workers).use { http ->
                val clientPoll = ApplicationUpdatePoll("client-test", UpdateComponent.CLIENT, UpdatePlatform.WINDOWS_X64, "Test client", "previous-version")
                suspend fun poll(body: ApplicationUpdatePoll): ApplicationUpdatePollResponse {
                    val response = http.perform(post("/api/updates/v1/client/poll").secureTestRequest("update-session")
                        .contentType("application/json").content(Json.encodeToString(body)))
                    assertEquals(200, response.status, response.contentAsString)
                    return Json.decodeFromString(response.contentAsString)
                }
                assertEquals(401, http.perform(get("/api/updates/v1/installations").secureTestRequest()).status)
                assertEquals(403, http.perform(post("/api/updates/v1/client/poll").secureTestRequest("update-session")
                    .header("Origin", "https://foreign.invalid").content(Json.encodeToString(clientPoll))).status)
                assertEquals(413, http.perform(post("/api/updates/v1/client/poll").secureTestRequest("update-session").content("x".repeat(9000))).status)
                assertEquals(null, poll(clientPoll).installation.request)
                assertFailsWith<IllegalArgumentException> { service.request(stranger, "client-test") }
                service.policy(user, "client-test", automatic = true)
                val queued = requireNotNull(poll(clientPoll).installation.request)
                assertEquals(ApplicationUpdatePhase.QUEUED, queued.phase)
                assertEquals(queued.id, service.request(user, "client-test").request?.id)
                val downloadPath = "/api/updates/v1/client/artifacts/client-test/${queued.id}"
                assertEquals(401, http.perform(get(downloadPath).secureTestRequest()).status)
                val packageResponse = http.perform(get(downloadPath).secureTestRequest("update-session"))
                assertEquals(200, packageResponse.status)
                assertContentEquals(bytes, packageResponse.contentAsByteArray)
                assertEquals("no-store", packageResponse.getHeader("Cache-Control"))
                service.cancel(user, "client-test")
                assertEquals(ApplicationUpdatePhase.CANCELLED, poll(clientPoll.copy(report = ApplicationUpdateReport(queued.id, ApplicationUpdatePhase.DOWNLOADING))).installation.request?.phase)
                val retry = requireNotNull(service.request(user, "client-test").request)
                for (phase in listOf(ApplicationUpdatePhase.DOWNLOADING, ApplicationUpdatePhase.READY, ApplicationUpdatePhase.INSTALLING)) {
                    assertEquals(phase, poll(clientPoll.copy(report = ApplicationUpdateReport(retry.id, phase))).installation.request?.phase)
                }
                assertFailsWith<IllegalArgumentException> { service.cancel(user, "client-test") }
                assertEquals(ApplicationUpdatePhase.FAILED, poll(clientPoll.copy(report = ApplicationUpdateReport(retry.id, ApplicationUpdatePhase.FAILED))).installation.request?.phase)
                assertEquals(retry.id, poll(clientPoll).installation.request?.id)
                val final = requireNotNull(service.request(user, "client-test").request)
                assertEquals(ApplicationUpdatePhase.SUCCEEDED, poll(clientPoll.copy(version = version)).installation.request?.phase)
                assertEquals(final.id, ApplicationUpdateService(repository, catalog).get("client-test").request?.id)
                val workerPoll = clientPoll.copy(component = UpdateComponent.WORKER)
                val workerResponse = http.perform(post("/api/updates/v1/worker/poll").secureTestRequest()
                    .header("Authorization", "Bearer ${"w".repeat(40)}").contentType("application/json").content(Json.encodeToString(workerPoll)))
                assertEquals(200, workerResponse.status, workerResponse.contentAsString)
                val registered = Json.decodeFromString<ApplicationUpdatePollResponse>(workerResponse.contentAsString).installation
                assertEquals(workerInstallationId(worker.id.value), registered.id)
                assertEquals(worker.id.value, registered.workerId)
                assertEquals(401, http.perform(post("/api/updates/v1/worker/request").secureTestRequest("update-session")).status)
                assertEquals(200, http.perform(post("/api/updates/v1/worker/request").secureTestRequest()
                    .header("Authorization", "Bearer ${"w".repeat(40)}")).status)
                assertEquals(400, http.perform(get("/api/updates/v1/worker/artifacts/client-test/${final.id}").secureTestRequest()
                    .header("Authorization", "Bearer ${"w".repeat(40)}")).status)
            }
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `tagged server resolves downloads from its exact release`() {
        assertEquals(
            "https://github.com/Lewik/gromozeka/releases/download/v2.3.4",
            resolveReleaseDownloadBaseUrl(
                serverVersion = "2.3.4",
                releaseRepository = "Lewik/gromozeka",
                configuredDownloadBaseUrl = null,
            ),
        )
    }

    @Test
    fun `development server resolves downloads from latest release`() {
        assertEquals(
            "https://github.com/Lewik/gromozeka/releases/latest/download",
            resolveReleaseDownloadBaseUrl(
                serverVersion = "0.0.0-dev",
                releaseRepository = "Lewik/gromozeka",
                configuredDownloadBaseUrl = null,
            ),
        )
    }

    @Test
    fun `prerelease server resolves downloads from its exact release`() {
        assertEquals(
            "https://github.com/Lewik/gromozeka/releases/download/v1.5.0-test.1",
            resolveReleaseDownloadBaseUrl(
                serverVersion = "1.5.0-test.1",
                releaseRepository = "Lewik/gromozeka",
                configuredDownloadBaseUrl = null,
            ),
        )
    }

    @Test
    fun `configured mirror overrides GitHub release location`() {
        assertEquals(
            "https://downloads.example/gromozeka",
            resolveReleaseDownloadBaseUrl(
                serverVersion = "2.3.4",
                releaseRepository = "ignored/repository",
                configuredDownloadBaseUrl = "https://downloads.example/gromozeka/",
            ),
        )
    }

    @Test
    fun `distribution catalog exposes Docker and standalone Server packages`() {
        val serverArtifacts = distributionArtifacts("https://downloads.example/gromozeka")
            .filter { it.component == DistributionComponent.SERVER }

        assertEquals(
            listOf(
                "gromozeka-server-stack.zip",
                "gromozeka-server-macos-arm64.tar.gz",
                "gromozeka-server-windows-x64.zip",
                "gromozeka-server-linux-x64.tar.gz",
            ),
            serverArtifacts.map { it.fileName },
        )
        assertEquals(
            serverArtifacts.map { "https://downloads.example/gromozeka/${it.fileName}" },
            serverArtifacts.map { it.downloadUrl },
        )
    }

    @Test
    fun `distribution catalog exposes the Browser Bridge`() {
        val bridge = distributionArtifacts("https://downloads.example/gromozeka")
            .single { it.component == DistributionComponent.BROWSER_BRIDGE }

        assertEquals(BrowserUseMcpPreset.BRIDGE_ARTIFACT_ID, bridge.id)
        assertEquals(BrowserUseMcpPreset.BRIDGE_FILE_NAME, bridge.fileName)
        assertEquals(
            "https://downloads.example/gromozeka/${BrowserUseMcpPreset.BRIDGE_FILE_NAME}",
            bridge.downloadUrl,
        )
    }
}
