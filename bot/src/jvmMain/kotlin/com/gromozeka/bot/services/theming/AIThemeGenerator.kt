package com.gromozeka.bot.services.theming

import com.gromozeka.bot.platform.ScreenCaptureController
import com.gromozeka.bot.services.SessionManager
import com.gromozeka.bot.services.SettingsService
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json
import java.io.File

/**
 * AI-powered service for generating themes from screenshots using Claude analysis.
 *
 * NEW APPROACH: Instead of running Claude in background, we create a regular tab/session
 * and let Claude Code work naturally with all available tools.
 */
class AIThemeGenerator(
    private val screenCaptureController: ScreenCaptureController,
    private val sessionManager: SessionManager,
    private val settingsService: SettingsService,
) {

    /**
     * Take a screenshot and prepare data for AI theme generation.
     * Returns the prepared initial message, or null if failed.
     */
    suspend fun prepareThemeGenerationData(coroutineScope: CoroutineScope): String? {
        return try {
            println("[AIThemeGenerator] Starting AI-powered theme generation process...")

            // Step 1: Take screenshot - let user select window
            println("[AIThemeGenerator] Taking screenshot - please select a window...")
            val screenshotPath = screenCaptureController.captureWindow()
            if (screenshotPath == null) {
                println("[AIThemeGenerator] Failed to capture window screenshot (cancelled by user or error)")
                return null
            }
            println("[AIThemeGenerator] Window screenshot saved to: $screenshotPath")

            // Step 2: Prepare working directory in gromozekaHome
            val aiWorkingDir = File(settingsService.gromozekaHome, "ai-theme-generator")
            if (!aiWorkingDir.exists()) {
                aiWorkingDir.mkdirs()
            }
            println("[AIThemeGenerator] Working directory: ${aiWorkingDir.absolutePath}")

            // Step 3: Copy theme examples to working directory
            copyExampleThemes(aiWorkingDir)

            // Step 4: Prepare comprehensive initial message with instructions
            val initialMessage = prepareInitialMessage(screenshotPath, aiWorkingDir)

            println("[AIThemeGenerator] AI theme generation data prepared successfully")
            println("[AIThemeGenerator] Working directory: ${aiWorkingDir.absolutePath}")

            initialMessage

        } catch (e: Exception) {
            println("[AIThemeGenerator] Error during AI theme generation setup: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    /**
     * Copy theme example screenshots from resources to working directory
     */
    private fun copyExampleThemes(aiWorkingDir: File) {
        try {
            val lightExamplePath = copyExampleScreenshot(aiWorkingDir, "light-theme-example.png")
            val darkExamplePath = copyExampleScreenshot(aiWorkingDir, "dark-theme-example.png")
            println("[AIThemeGenerator] Theme examples copied:")
            println("  - Light: $lightExamplePath")
            println("  - Dark: $darkExamplePath")
        } catch (e: Exception) {
            println("[AIThemeGenerator] Error copying theme examples: ${e.message}")
        }
    }

    /**
     * Prepare initial message using template replacement
     */
    private fun prepareInitialMessage(screenshotPath: String, aiWorkingDir: File): String {
        return try {
            // Load template from resources
            val templateResource =
                this::class.java.getResourceAsStream("/ai-instructions/theme-generation-task-template.md")
            val template = templateResource?.bufferedReader()?.use { it.readText() }
                ?: throw IllegalStateException("Could not load theme generation task template")

            // Load detailed guide content
            val guideResource = this::class.java.getResourceAsStream("/ai-instructions/theme-generation-guide.md")
            val guideContent = guideResource?.bufferedReader()?.use { it.readText() }
                ?: throw IllegalStateException("Could not load theme generation guide")

            // Get serialized theme examples
            val lightTheme = com.gromozeka.bot.services.theming.data.LightTheme()
            val darkTheme = com.gromozeka.bot.services.theming.data.DarkTheme()
            // Create JSON encoder that includes default values and pretty printing
            val jsonWithDefaults = Json {
                encodeDefaults = true
                prettyPrint = true
            }
            val lightThemeJson = jsonWithDefaults.encodeToString(
                com.gromozeka.bot.services.theming.data.LightTheme.serializer(),
                lightTheme
            )
            val darkThemeJson = jsonWithDefaults.encodeToString(
                com.gromozeka.bot.services.theming.data.DarkTheme.serializer(),
                darkTheme
            )


            // Prepare output directory path
            val themesOutputDir = File(settingsService.gromozekaHome, "themes")
            if (!themesOutputDir.exists()) {
                themesOutputDir.mkdirs()
            }

            // Replace template variables
            template
                .replace("{SCREENSHOT_PATH}", screenshotPath)
                .replace("{LIGHT_THEME_JSON}", lightThemeJson)
                .replace("{DARK_THEME_JSON}", darkThemeJson)
                .replace("{OUTPUT_PATH}", themesOutputDir.absolutePath)
                .replace("{TIMESTAMP}", System.currentTimeMillis().toString())
                .replace("{DETAILED_GUIDE_CONTENT}", guideContent)

        } catch (e: Exception) {
            println("[AIThemeGenerator] Error preparing initial message: ${e.message}")
            // Fallback to simple message
            "Please analyze the screenshot at `$screenshotPath` and generate a Material Design 3 theme JSON. Save the result to the themes directory."
        }
    }

    /**
     * Get the working directory path for AI theme generation
     */
    fun getWorkingDirectory(): String {
        return File(settingsService.gromozekaHome, "ai-theme-generator").absolutePath
    }

    /**
     * Copy example screenshot from resources to AI working directory
     */
    private fun copyExampleScreenshot(aiWorkingDir: File, filename: String): String {
        return try {
            val resourceStream = this::class.java.getResourceAsStream("/theme-examples/$filename")
            if (resourceStream != null) {
                val targetFile = File(aiWorkingDir, filename)
                targetFile.outputStream().use { output ->
                    resourceStream.use { input ->
                        input.copyTo(output)
                    }
                }
                targetFile.absolutePath
            } else {
                println("[AIThemeGenerator] Warning: Example screenshot $filename not found in resources")
                filename // Return filename as fallback
            }
        } catch (e: Exception) {
            println("[AIThemeGenerator] Error copying example screenshot $filename: ${e.message}")
            filename // Return filename as fallback
        }
    }
}