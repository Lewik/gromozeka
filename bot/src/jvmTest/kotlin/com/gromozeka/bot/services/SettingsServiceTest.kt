package com.gromozeka.bot.services

import com.gromozeka.bot.settings.AppMode
import com.gromozeka.bot.settings.Settings
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.io.File
import java.nio.file.Files

class SettingsServiceTest : FunSpec({

    lateinit var tempDir: File
    lateinit var settingsFile: File
    lateinit var service: SettingsService

    beforeEach {
        tempDir = Files.createTempDirectory("settings-test").toFile()
        settingsFile = File(tempDir, ".gromozeka/settings.json")

        // Override home directory for testing
        System.setProperty("user.home", tempDir.absolutePath)

        service = SettingsService()
        service.initialize()
    }

    afterEach {
        tempDir.deleteRecursively()
    }

    test("should load default settings when file doesn't exist") {
        val settings = service.settings

        // Mode defaults to PROD when no env var is set
        service.mode shouldBe AppMode.PROD
        settings.enableTts shouldBe true
        settings.enableStt shouldBe true
        settings.autoSend shouldBe true
    }

    test("should save and load settings from file") {
        val customSettings = Settings(
            enableTts = false,
            enableStt = false,
            autoSend = false
        )

        service.saveSettings(customSettings)

        // Create new service to test loading
        val newService = SettingsService()
        newService.initialize()
        val loaded = newService.settings

        newService.mode shouldBe AppMode.PROD  // Mode from env var (defaults to PROD)
        loaded.enableTts shouldBe false
        loaded.enableStt shouldBe false
        loaded.autoSend shouldBe false
    }

    // Note: Testing environment variables is challenging in JVM
    // These tests document expected behavior but can't dynamically test it
    /*
    test("should determine mode from GROMOZEKA_MODE env var") {
        // Would need GROMOZEKA_MODE=dev
        // Expected: service.mode shouldBe AppMode.DEV
    }
    
    test("should support different GROMOZEKA_MODE values") {
        // GROMOZEKA_MODE=dev -> AppMode.DEV
        // GROMOZEKA_MODE=development -> AppMode.DEV  
        // GROMOZEKA_MODE=prod -> AppMode.PROD
        // GROMOZEKA_MODE=production -> AppMode.PROD
        // GROMOZEKA_MODE not set -> AppMode.PROD (default)
    }
    */

    test("should update settings flow when settings change") {
        val initial = service.settingsFlow.value

        val updated = Settings(
            enableTts = false,  // Changed from default
            enableStt = false   // Changed from default
        )
        service.saveSettings(updated)

        service.settingsFlow.value shouldBe updated
        service.settingsFlow.value shouldNotBe initial
    }

    test("should update settings using block syntax") {
        service.saveSettings {
            copy(
                enableTts = false
            )
        }

        service.settings.enableTts shouldBe false
    }

    test("should reload settings from file") {
        // Save initial settings with specific values
        service.saveSettings(Settings(enableTts = true))

        // Manually write different settings to file
        settingsFile.parentFile.mkdirs()
        settingsFile.writeText(
            """
            {
                "enableTts": false,
                "enableStt": true,
                "autoSend": true
            }
        """.trimIndent()
        )

        // Reload
        service.reloadSettings()

        service.settings.enableTts shouldBe false
    }

    test("should provide access to settings") {
        service.saveSettings { copy(enableTts = false) }

        service.settings.enableTts shouldBe false
    }

    test("should handle corrupted settings file gracefully") {
        settingsFile.parentFile.mkdirs()
        settingsFile.writeText("invalid json")

        val service = SettingsService()
        service.initialize()

        // Should load defaults
        service.mode shouldBe AppMode.PROD
    }

    test("settings flow should be accessible") {
        val flow = service.settingsFlow
        flow shouldNotBe null

        service.saveSettings { copy(enableTts = false) }

        flow.value?.enableTts shouldBe false
    }

    test("should create default settings file on first run") {
        val service = SettingsService()
        service.initialize()

        val settingsFile = File(service.gromozekaHome, "settings.json")
        settingsFile.exists() shouldBe true
    }

    test("settings should always save to file") {
        service.saveSettings(Settings(enableTts = false))

        val settingsFile = File(service.gromozekaHome, "settings.json")
        settingsFile.exists() shouldBe true
    }

    test("should regenerate settings.dist.yaml with default values") {
        val projectRoot = File(System.getProperty("user.dir"))
        val distFile = File(projectRoot, "dev-data/.gromozeka/settings.dist.yaml")
        
        // Create directory if it doesn't exist
        distFile.parentFile?.mkdirs()
        
        val defaultSettings = Settings() // Use all default values
        
        // Generate YAML content with comments
        val yamlContent = buildString {
            appendLine("# Gromozeka Default Settings Template")
            appendLine("# Copy this file to settings.json and modify as needed")
            appendLine()
            
            appendLine("# TTS (Text-to-Speech) Configuration")
            appendLine("enableTts: ${defaultSettings.enableTts}")
            appendLine("ttsSpeed: ${defaultSettings.ttsSpeed}  # Speech rate: 0.25 (slowest) to 4.0 (fastest), 1.0 = normal speed")
            appendLine()
            
            appendLine("# STT (Speech-to-Text) Configuration")
            appendLine("enableStt: ${defaultSettings.enableStt}")
            appendLine("sttMainLanguage: \"${defaultSettings.sttMainLanguage}\"  # Language code - supports ISO 639-1 (e.g., \"en\", \"ru\") and 639-3 codes for GPT-4o models")
            appendLine()
            
            appendLine("# Voice Control Settings")
            appendLine("autoSend: ${defaultSettings.autoSend}")
            appendLine("globalPttHotkeyEnabled: ${defaultSettings.globalPttHotkeyEnabled}")
            appendLine("muteSystemAudioDuringPTT: ${defaultSettings.muteSystemAudioDuringPTT}")
            appendLine()
            
            appendLine("# Claude Model Configuration") 
            appendLine("claudeModel: \"${defaultSettings.claudeModel}\"")
            appendLine()
            
            appendLine("# UI Settings")
            appendLine("showOriginalJson: ${defaultSettings.showOriginalJson}")
            appendLine()
            
            appendLine("# System Settings")
            appendLine("includeCurrentTime: ${defaultSettings.includeCurrentTime}")
        }
        
        // Write the file
        distFile.writeText(yamlContent)
        
        // Verify the file exists and contains expected content
        distFile.exists() shouldBe true
        val content = distFile.readText()
        content.contains("enableTts: ${defaultSettings.enableTts}") shouldBe true
        content.contains("ttsSpeed: ${defaultSettings.ttsSpeed}") shouldBe true
        content.contains("autoSend: ${defaultSettings.autoSend}") shouldBe true
        content.contains("claudeModel: \"${defaultSettings.claudeModel}\"") shouldBe true
        
        println("✅ settings.dist.yaml regenerated successfully at: ${distFile.absolutePath}")
    }
})