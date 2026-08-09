package com.gromozeka.infrastructure.ai.copilot

import com.github.copilot.CopilotClient
import com.github.copilot.rpc.CopilotClientMode
import com.github.copilot.rpc.CopilotClientOptions
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.service.SettingsProvider
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.withTimeoutOrNull
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap

@Service
internal class GitHubCopilotClientPool(
    private val settingsProvider: SettingsProvider,
) {
    private val clients = ConcurrentHashMap<ClientKey, CompletableFuture<GitHubCopilotClientHandle>>()

    suspend fun acquire(connection: AiConnection.GitHubCopilot): GitHubCopilotClientHandle {
        val key = connection.clientKey()
        val future = clients.computeIfAbsent(key, ::startClient)
        return try {
            future.awaitCancellable(cancelFutureOnCancellation = false)
        } catch (error: Throwable) {
            clients.remove(key, future)
            throw error
        }
    }

    suspend fun discardIfUnhealthy(
        connection: AiConnection.GitHubCopilot,
        handle: GitHubCopilotClientHandle,
    ) {
        val key = connection.clientKey()
        val future = clients[key] ?: return
        val healthy = withTimeoutOrNull(HEALTH_CHECK_TIMEOUT_MILLIS) {
            runCatching {
                handle.client.ping("gromozeka-health-check")
                    .awaitCancellable(cancelFutureOnCancellation = false)
            }.isSuccess
        } ?: false
        if (!healthy && clients.remove(key, future)) {
            handle.client.close()
        }
    }

    @PreDestroy
    fun close() {
        clients.values.forEach { future ->
            if (future.isDone && !future.isCompletedExceptionally && !future.isCancelled) {
                runCatching { future.getNow(null)?.client?.close() }
            }
        }
        clients.clear()
    }

    private fun startClient(key: ClientKey): CompletableFuture<GitHubCopilotClientHandle> {
        Files.createDirectories(key.copilotHome)
        Files.createDirectories(key.workingDirectory)
        val client = CopilotClient(
            CopilotClientOptions()
                .setMode(CopilotClientMode.EMPTY)
                .setCliPath(key.executablePath)
                .setCopilotHome(key.copilotHome.toString())
                .setCwd(key.workingDirectory.toString())
                .setEnvironment(copilotEnvironment())
                .setUseLoggedInUser(key.authMode == AiConnection.GitHubCopilotAuthMode.SERVER_CLI)
                .setSessionIdleTimeoutSeconds(key.sessionIdleTimeoutSeconds)
                .setLogLevel("error")
        )
        return client.start().handle { _, error ->
            if (error != null) {
                client.close()
                throw CompletionException(error)
            }
            GitHubCopilotClientHandle(client, key.workingDirectory)
        }
    }

    private fun AiConnection.GitHubCopilot.clientKey(): ClientKey {
        val home = copilotHomePath
            ?.let(::expandHome)
            ?: when (authMode) {
                AiConnection.GitHubCopilotAuthMode.SERVER_CLI ->
                    Path.of(System.getProperty("user.home"), ".copilot")
                AiConnection.GitHubCopilotAuthMode.PER_USER_TOKEN ->
                    Path.of(settingsProvider.homeDirectory, "copilot", id.value)
            }
        return ClientKey(
            connectionId = id.value,
            executablePath = executablePath,
            copilotHome = home.toAbsolutePath().normalize(),
            workingDirectory = Path.of(settingsProvider.homeDirectory, "copilot", "work", id.value)
                .toAbsolutePath()
                .normalize(),
            authMode = authMode,
            sessionIdleTimeoutSeconds = sessionIdleTimeoutSeconds,
        )
    }

    private fun expandHome(value: String): Path =
        if (value == "~" || value.startsWith("~/")) {
            Path.of(System.getProperty("user.home"), value.removePrefix("~/").removePrefix("~"))
        } else {
            Path.of(value)
        }

    private fun copilotEnvironment(): Map<String, String> =
        System.getenv().filterKeys { name ->
            !isIsolatedEnvironmentVariable(name)
        }

    private fun isIsolatedEnvironmentVariable(name: String): Boolean =
        ISOLATED_ENVIRONMENT_VARIABLES.any { variable -> variable.equals(name, ignoreCase = true) } ||
            name.startsWith("COPILOT_PROVIDER_", ignoreCase = true) ||
            name.startsWith("COPILOT_OTEL_", ignoreCase = true) ||
            name.startsWith("OTEL_", ignoreCase = true)

    private data class ClientKey(
        val connectionId: String,
        val executablePath: String,
        val copilotHome: Path,
        val workingDirectory: Path,
        val authMode: AiConnection.GitHubCopilotAuthMode,
        val sessionIdleTimeoutSeconds: Int,
    )

    private companion object {
        const val HEALTH_CHECK_TIMEOUT_MILLIS = 5_000L
        val ISOLATED_ENVIRONMENT_VARIABLES = setOf(
            "COPILOT_API_URL",
            "COPILOT_CUSTOM_INSTRUCTIONS_DIRS",
            "COPILOT_GITHUB_TOKEN",
            "COPILOT_MODEL",
            "COPILOT_OFFLINE",
            "COPILOT_SDK_AUTH_TOKEN",
            "GH_TOKEN",
            "GITHUB_COPILOT_API_TOKEN",
            "GITHUB_TOKEN",
        )
    }
}

internal data class GitHubCopilotClientHandle(
    val client: CopilotClient,
    val workingDirectory: Path,
)
