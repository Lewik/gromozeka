package com.gromozeka.server.testsupport.app

import com.gromozeka.domain.model.Settings
import com.gromozeka.domain.model.UserProfile
import com.gromozeka.domain.model.UserProfileAiDefaults
import com.gromozeka.domain.model.ai.AiModelConfiguration
import com.gromozeka.domain.model.ai.AiRuntimeAssignment
import com.gromozeka.domain.model.ai.AiRuntimeSelection
import com.gromozeka.infrastructure.ai.openai.subscription.OpenAiSubscriptionConfig
import com.gromozeka.infrastructure.ai.openai.subscription.OpenAiSubscriptionSession
import com.gromozeka.server.GromozekaServerApplication
import com.gromozeka.server.testsupport.config.E2eSupportConfig
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class ServerTestHarness(
    private val settings: Settings = defaultSettings(),
    private val subscriptionSession: OpenAiSubscriptionSession? = defaultSubscriptionSession(),
    private val subscriptionConfigMirrorPath: Path? = null,
    private val systemProperties: Map<String, String> = emptyMap(),
    private val additionalSources: List<Class<*>> = emptyList(),
    private val customizeHome: (Path) -> Unit = {},
) : Closeable {
    val homeDirectory: Path = prepareHomeDirectory()
    private val previousSystemProperties: Map<String, String?>
    val context: ConfigurableApplicationContext

    init {
        val properties = buildMap {
            put("GROMOZEKA_MODE", "test")
            put("GROMOZEKA_HOME", homeDirectory.toString())
            put("logging.file.path", homeDirectory.resolve("logs").toString())
            put("gromozeka.runtime.rabbit.enabled", "false")
            put("gromozeka.runtime.server.enabled", "true")
            put("gromozeka.runtime.worker.enabled", "true")
            putAll(systemProperties)
        }
        previousSystemProperties = applySystemProperties(properties)
        context = try {
            SpringApplicationBuilder(GromozekaServerApplication::class.java)
                .sources(E2eSupportConfig::class.java, *additionalSources.toTypedArray())
                .web(WebApplicationType.NONE)
                .profiles("e2e")
                .run()
        } catch (error: Throwable) {
            restoreSystemProperties()
            throw error
        }
    }

    override fun close() {
        runCatching { context.close() }
        runCatching { mirrorSubscriptionConfigBack() }
        restoreSystemProperties()
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

    private fun applySystemProperties(properties: Map<String, String>): Map<String, String?> =
        properties.mapValues { (key, value) ->
            System.getProperty(key).also { System.setProperty(key, value) }
        }

    private fun restoreSystemProperties() {
        previousSystemProperties.forEach { (key, value) ->
            if (value == null) {
                System.clearProperty(key)
            } else {
                System.setProperty(key, value)
            }
        }
    }

    private fun mirrorSubscriptionConfigBack() {
        val targetPath = subscriptionConfigMirrorPath ?: return
        val sourcePath = homeDirectory.resolve("openai-subscription.json")
        if (!sourcePath.exists()) return

        Files.createDirectories(targetPath.parent)
        Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING)
    }

    companion object {
        fun defaultSettings(): Settings = Settings(
            userProfile = openAiSubscriptionProfile("gpt-5.5"),
        )

        fun openAiSubscriptionRuntimeSelection(): AiRuntimeSelection =
            AiRuntimeSelection(AiModelConfiguration.Id("openai-subscription-gpt-5.5"))

        fun openAiSubscriptionProfile(modelName: String): UserProfile {
            val selection = openAiSubscriptionRuntimeSelection()
            return UserProfile(
                aiSettings = UserProfile.AiSettings(
                    connections = UserProfileAiDefaults.connections(),
                    modelConfigurations = UserProfileAiDefaults.modelConfigurations().map { configuration ->
                        if (configuration.id == selection.modelConfigurationId) {
                            configuration.copy(
                                providerModelId = modelName,
                                displayName = "OpenAI subscription $modelName",
                            )
                        } else {
                            configuration
                        }
                    },
                    runtimeAssignments = UserProfileAiDefaults.runtimeAssignments().map {
                        if (it.purpose == AiRuntimeAssignment.Purpose.DEFAULT_CHAT) {
                            it.copy(selection = selection)
                        } else {
                            it
                        }
                    },
                )
            )
        }

        fun defaultSubscriptionSession(): OpenAiSubscriptionSession = OpenAiSubscriptionSession(
            accessToken = "test-access-token",
            refreshToken = "test-refresh-token",
            idToken = null,
            accountId = "test-account-id",
            expiresAt = Clock.System.now().toEpochMilliseconds() + 86_400_000L,
        )

        fun subscriptionSessionFromConfig(configPath: Path): OpenAiSubscriptionSession? {
            if (!configPath.exists()) return null
            val config = appTestJson.decodeFromString<OpenAiSubscriptionConfig>(configPath.readText())
            val accessToken = config.accessToken ?: return null
            val refreshToken = config.refreshToken ?: return null
            val expiresAt = config.expiresAt ?: return null
            return OpenAiSubscriptionSession(
                accessToken = accessToken,
                refreshToken = refreshToken,
                idToken = config.idToken,
                accountId = config.accountId,
                expiresAt = expiresAt,
            )
        }

        val appTestJson = Json {
            prettyPrint = true
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
    }
}

fun String.sanitizePathSegment(): String =
    replace(Regex("[^A-Za-z0-9._-]+"), "-")
        .trim('-')
        .ifBlank { "unnamed" }
