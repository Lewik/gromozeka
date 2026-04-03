package com.gromozeka.presentation.testsupport.app

import androidx.compose.runtime.Composable
import com.gromozeka.domain.model.AIProvider
import com.gromozeka.infrastructure.ai.openai.subscription.OpenAiSubscriptionConfig
import com.gromozeka.infrastructure.ai.openai.subscription.OpenAiSubscriptionSession
import com.gromozeka.presentation.AppBootstrap
import com.gromozeka.presentation.AppBootstrapOptions
import com.gromozeka.presentation.AppInitializationOptions
import com.gromozeka.presentation.StartedApp
import com.gromozeka.presentation.model.Settings
import com.gromozeka.presentation.ui.GromozekaApp
import com.gromozeka.presentation.testsupport.config.E2eSupportConfig
import com.gromozeka.presentation.testsupport.network.OpenAiSubscriptionReplayServer
import kotlinx.datetime.Clock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText

/**
 * Owns the started app instance and the temporary environment around it.
 */
class AppTestHarness(
    private val settings: Settings = defaultSettings(),
    private val subscriptionSession: OpenAiSubscriptionSession? = defaultSubscriptionSession(),
    private val replayServer: OpenAiSubscriptionReplayServer? = null,
    private val initialization: AppInitializationOptions = defaultInitialization,
    private val customizeHome: (Path) -> Unit = {},
) : Closeable {
    val homeDirectory: Path = prepareHomeDirectory()
    private val startedApp: StartedApp = AppBootstrap.start(
        AppBootstrapOptions(
            modeOverride = "test",
            springProfileOverride = "e2e",
            logPathOverride = homeDirectory.resolve("logs").toString(),
            systemProperties = buildMap {
                put("GROMOZEKA_HOME", homeDirectory.toString())
                replayServer?.let { put("gromozeka.ai.openai-subscription.responses-url", it.responsesUrl) }
            },
            additionalSources = listOf(E2eSupportConfig::class.java),
            initialization = initialization,
        )
    )

    @Composable
    fun Content(skipLoadingScreen: Boolean = true) {
        GromozekaApp(
            appComponents = startedApp.appComponents,
            skipLoadingScreen = skipLoadingScreen,
        )
    }

    override fun close() {
        runCatching { startedApp.close() }
        runCatching { replayServer?.close() }
        runCatching { homeDirectory.toFile().deleteRecursively() }
    }

    private fun prepareHomeDirectory(): Path {
        val homeDirectory = Files.createTempDirectory("gromozeka-e2e-")
        writeSettings(homeDirectory, settings)
        writeMcpConfig(homeDirectory)
        writeSubscriptionConfig(homeDirectory, subscriptionSession)
        customizeHome(homeDirectory)
        return homeDirectory
    }

    private fun writeSettings(homeDirectory: Path, settings: Settings) {
        homeDirectory.resolve("settings.json").writeText(appTestJson.encodeToString(settings))
    }

    private fun writeMcpConfig(homeDirectory: Path) {
        homeDirectory.resolve("mcp.json").writeText("""{"mcpServers":{}}""")
    }

    private fun writeSubscriptionConfig(
        homeDirectory: Path,
        session: OpenAiSubscriptionSession?,
    ) {
        val configPath = homeDirectory.resolve("openai-subscription.json")
        if (session == null) {
            configPath.deleteIfExists()
            return
        }

        configPath.writeText(
            appTestJson.encodeToString(
                OpenAiSubscriptionConfig(
                    accessToken = session.accessToken,
                    refreshToken = session.refreshToken,
                    idToken = session.idToken,
                    accountId = session.accountId,
                    expiresAt = session.expiresAt,
                )
            )
        )
    }

    private companion object {
        val defaultInitialization = AppInitializationOptions(
            initializeGlobalHotkeys = false,
            initializePttRouter = false,
            startTtsQueueService = false,
            startTtsAutoplayService = false,
        )

        fun defaultSettings(): Settings = Settings(
            enableTts = false,
            enableStt = false,
            autoSend = false,
            defaultAiProvider = AIProvider.OPEN_AI_SUBSCRIPTION,
            openAiModel = "gpt-5.3-codex",
            enableBraveSearch = false,
            enableJinaReader = false,
            vectorStorageEnabled = false,
            graphStorageEnabled = false,
        )

        fun defaultSubscriptionSession(): OpenAiSubscriptionSession = OpenAiSubscriptionSession(
            accessToken = "test-access-token",
            refreshToken = "test-refresh-token",
            idToken = null,
            accountId = "test-account-id",
            expiresAt = Clock.System.now().toEpochMilliseconds() + 86_400_000L,
        )

        val appTestJson = Json {
            prettyPrint = true
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
    }
}
