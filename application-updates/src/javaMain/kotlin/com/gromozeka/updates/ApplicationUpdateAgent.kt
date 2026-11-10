package com.gromozeka.updates

import com.gromozeka.domain.model.*
import com.gromozeka.remote.protocol.*
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URI
import java.security.MessageDigest

interface ApplicationUpdateInstaller {
    val unsupportedReason: String? get() = null
    suspend fun prepare(): Boolean = true
    suspend fun install(file: File, update: ApplicationUpdateRequest, store: UpdateLocalStore)
    suspend fun resume() {}
    suspend fun installationStopped(update: ApplicationUpdateRequest, store: UpdateLocalStore): Boolean = false
    suspend fun confirmed(update: ApplicationUpdateRequest) {}
    suspend fun cancelPending(update: ApplicationUpdateRequest, store: UpdateLocalStore): Boolean = false
}

class UpdateInstallationRejected(message: String) : IllegalArgumentException(message)

data class ApplicationUpdateView(val installation: ApplicationInstallation? = null, val available: ApplicationUpdateArtifact? = null, val error: String? = null)

class ApplicationUpdateAgent(
    serverUrl: String,
    private val httpClient: HttpClient,
    private val component: UpdateComponent,
    private val platform: UpdatePlatform,
    private val label: String,
    private val version: String,
    private val store: UpdateLocalStore,
    private val installer: ApplicationUpdateInstaller,
    private val credential: String? = null,
    private val allowAutomaticInstall: () -> Boolean = { false },
    private val closeHttpClient: Boolean = false,
) : AutoCloseable {
    private val base = serverUrl.replaceFirst("wss://", "https://").replaceFirst("ws://", "http://").trimEnd('/').removeSuffix("/ws")
    private val kind = if (component == UpdateComponent.WORKER) "worker" else "client"
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val http = httpClient.config {
        followRedirects = false
        install(HttpTimeout) { requestTimeoutMillis = 30_000; connectTimeoutMillis = 15_000; socketTimeoutMillis = 60_000 }
    }
    private val mutex = Mutex()
    private val mutableView = MutableStateFlow(ApplicationUpdateView())
    val view = mutableView.asStateFlow()
    private var job: Job? = null

    private suspend fun <T> exclusive(block: suspend () -> T): T =
        mutex.withLock { withContext(Dispatchers.IO) { store.withAgentLock(block) } }

    init {
        val url = URI(base)
        require(url.scheme == "https" || url.scheme == "http" && url.host in setOf("localhost", "127.0.0.1", "::1", "[::1]")) { "Updates require HTTPS" }
        require(url.host != null && (url.port == -1 || url.port in 1..65535) && url.userInfo == null && url.query == null && url.fragment == null && url.path.isNullOrEmpty()) { "Invalid update server URL" }
    }

    fun start(scope: CoroutineScope) {
        check(job == null)
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try { checkNow() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { mutableView.value = mutableView.value.copy(error = "Update service unavailable (${error.javaClass.simpleName})") }
                delay(30_000)
            }
        }
    }

    suspend fun checkNow() = exclusive {
        val response = poll()
        val request = response.installation.request ?: return@exclusive
        if (request.phase.terminal) {
            installer.resume()
            if (request.phase == ApplicationUpdatePhase.SUCCEEDED) installer.confirmed(request)
            return@exclusive
        }
        if (installer.unsupportedReason != null) return@exclusive
        if (request.phase in setOf(ApplicationUpdatePhase.INSTALLING, ApplicationUpdatePhase.WAITING_FOR_USER)) {
            if (installer.installationStopped(request, store)) {
                report(request, ApplicationUpdatePhase.FAILED, "Installer stopped before the new version started; retry explicitly")
                installer.resume()
            }
            return@exclusive
        }
        val file = File(store.directory, request.artifact.fileName)
        if (request.phase != ApplicationUpdatePhase.READY) {
            report(request, ApplicationUpdatePhase.DOWNLOADING)
            try {
                download(request, file, response.installation.id)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                report(request, ApplicationUpdatePhase.FAILED, "Package download or verification failed (${error.javaClass.simpleName})")
                return@exclusive
            }
            val ready = report(request, ApplicationUpdatePhase.READY)
            if (ready.installation.request?.phase != ApplicationUpdatePhase.READY) return@exclusive
        }
        if (component == UpdateComponent.WORKER || response.installation.automatic && allowAutomaticInstall()) installReadyLocked()
    }

    suspend fun requestUpdate() = exclusive {
        val installation = poll().installation
        post<ApplicationInstallation>(if (component == UpdateComponent.WORKER) "/worker/request" else "/installations/${installation.id}/request", "{}")
    }

    suspend fun setAutomatic(enabled: Boolean) = exclusive {
        val installation = poll().installation
        post<ApplicationInstallation>(if (component == UpdateComponent.WORKER) "/worker/policy" else "/installations/${installation.id}/policy", json.encodeToString(ApplicationUpdatePolicy(enabled)))
        poll()
    }

    suspend fun installReady(): Boolean = exclusive { installReadyLocked() }

    suspend fun cancelPendingInstallation() = exclusive {
        val request = poll().installation.request ?: return@exclusive
        require(request.phase == ApplicationUpdatePhase.WAITING_FOR_USER) { "Installation cannot be cancelled in this state" }
        check(installer.cancelPending(request, store)) { "The operating system did not cancel this installation" }
        report(request, ApplicationUpdatePhase.FAILED, "Installation cancelled by user")
        installer.resume()
    }

    private suspend fun installReadyLocked(): Boolean {
        val current = poll().installation.request ?: return false
        if (current.phase != ApplicationUpdatePhase.READY) return false
        val file = File(store.directory, current.artifact.fileName)
        if (!verify(file, current.artifact)) {
            report(current, ApplicationUpdatePhase.FAILED, "Downloaded package is missing or has changed")
            return false
        }
        if (!installer.prepare()) return false
        var handedOff = false
        try {
            val accepted = report(current, ApplicationUpdatePhase.INSTALLING).installation.request
            if (accepted?.id != current.id || accepted.phase != ApplicationUpdatePhase.INSTALLING) {
                installer.resume()
                return false
            }
            File(store.directory, "handoff-${current.id}").writeText(System.currentTimeMillis().toString())
            handedOff = true
            installer.install(file, current, store)
            return true
        } catch (cancelled: CancellationException) {
            if (!handedOff) withContext(NonCancellable) { installer.resume() }
            throw cancelled
        }
        catch (error: Exception) {
            if (handedOff && error !is UpdateInstallationRejected && !installer.installationStopped(current, store)) {
                mutableView.value = mutableView.value.copy(error = "Waiting for the operating system to confirm the installation outcome")
                return false
            }
            installer.resume()
            report(current, ApplicationUpdatePhase.FAILED, if (error is UpdateInstallationRejected) error.message else "Installation could not start (${error.javaClass.simpleName})")
            return false
        }
    }

    private suspend fun poll(): ApplicationUpdatePollResponse {
        val state = store.read()
        val result = post<ApplicationUpdatePollResponse>("/$kind/poll", json.encodeToString(ApplicationUpdatePoll(
            state.installationId, component, platform, label, version, state.report, installer.unsupportedReason,
        )))
        val request = result.installation.request
        store.acceptPoll(state, result.installation.id, request?.let { ApplicationUpdateReport(it.id, it.phase, it.message) })
        mutableView.value = ApplicationUpdateView(result.installation, result.available)
        return result
    }

    private suspend fun report(request: ApplicationUpdateRequest, phase: ApplicationUpdatePhase, message: String? = null): ApplicationUpdatePollResponse {
        store.report(ApplicationUpdateReport(request.id, phase, message))
        return poll()
    }

    private suspend inline fun <reified T> post(path: String, body: String): T {
        val response = http.post("$base/api/updates/v1$path") {
            credential?.let { bearerAuth(it) }
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        check(response.status.value in 200..299) { "Update request failed (${response.status.value})" }
        val bytes = response.bodyAsChannel().readRemainingBytes(128 * 1024)
        return json.decodeFromString(bytes.decodeToString(throwOnInvalidSequence = true))
    }

    private suspend fun download(update: ApplicationUpdateRequest, destination: File, installationId: String) {
        if (verify(destination, update.artifact)) return
        val temporary = File(destination.path + ".part")
        try {
            http.prepareGet("$base/api/updates/v1/$kind/artifacts/$installationId/${update.id}") {
                credential?.let { bearerAuth(it) }
                timeout { requestTimeoutMillis = 30 * 60 * 1000L }
            }.execute { response ->
                check(response.status == HttpStatusCode.OK)
                val expected = update.artifact.size
                response.headers[HttpHeaders.ContentLength]?.let { require(it.toLong() == expected) }
                val channel = response.bodyAsChannel()
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var size = 0L
                    while (true) {
                        val count = channel.readAvailable(buffer)
                        if (count == -1) break
                        size += count
                        require(size <= expected) { "Update package exceeds declared size" }
                        output.write(buffer, 0, count)
                    }
                    require(size == expected)
                    output.fd.sync()
                }
            }
            require(verify(temporary, update.artifact)) { "Update checksum mismatch" }
            moveUpdateFile(temporary, destination)
        } finally { temporary.delete() }
    }

    private fun verify(file: File, artifact: ApplicationUpdateArtifact): Boolean {
        if (!file.isFile || file.length() != artifact.size) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count == -1) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == artifact.sha256
    }

    override fun close() { job?.cancel(); http.close(); if (closeHttpClient) httpClient.close() }
}

private suspend fun io.ktor.utils.io.ByteReadChannel.readRemainingBytes(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = readAvailable(buffer)
        if (count == -1) break
        require(output.size() + count <= limit) { "Update response exceeds limit" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
