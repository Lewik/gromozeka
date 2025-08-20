package com.gromozeka.bot

import com.gromozeka.bot.model.StreamJsonLine
import com.gromozeka.bot.services.SettingsService
import com.gromozeka.bot.services.StreamToChatMessageMapper
import com.gromozeka.bot.settings.Settings
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Disabled
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Simple tests using real stream logs from ~/.gromozeka/streamlogs/
 * Tests both deserialization of StreamJsonLine and mapping to ChatMessage
 */
class RealStreamLogsTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val settingsService = mockk<SettingsService>().apply {
        every { settingsFlow } returns MutableStateFlow(Settings())
    }

    private val mapper = StreamToChatMessageMapper(
        settingsService = settingsService,
        scope = CoroutineScope(Dispatchers.Unconfined)
    )

    // DISABLED - This test is for research purposes only and should not run in CI
    @Disabled
    @Test
    fun testStreamLogsDeserialization() {

        val streamLogsDir =
            File(System.getProperty("user.home"), ".gromozeka/streamlogs/Users-lewik-code-gromozeka-dev")

        if (!streamLogsDir.exists()) {
            println("Stream logs directory not found: ${streamLogsDir.absolutePath}")
            println("Run Gromozeka first to generate stream logs")
            return
        }

        val files = streamLogsDir.listFiles()
        if (files == null || files.isEmpty()) {
            println("No files found in stream logs directory")
            return
        }

        val jsonlFiles = files.filter { it.name.endsWith(".jsonl") }
        if (jsonlFiles.isEmpty()) {
            println("No .jsonl files found")
            return
        }

        val newestFile = jsonlFiles.maxByOrNull { it.lastModified() }!!
        println("Testing file: ${newestFile.name}")

        val lines = newestFile.readLines().filter { it.trim().isNotEmpty() }
        var successCount = 0
        var errorCount = 0

        for ((index, line) in lines.withIndex()) {
            try {
                val streamMessage = json.decodeFromString<StreamJsonLine>(line.trim())
                assertNotNull(streamMessage)
                successCount++

                println("Line $index: ${streamMessage::class.simpleName} - ${getMessageInfo(streamMessage)}")

            } catch (e: Exception) {
                errorCount++
                println("Line $index: ERROR - ${e.message}")
                println("  Content: ${line.take(100)}...")
            }
        }

        println("Results: $successCount successful, $errorCount errors out of ${lines.size} lines")

        val successRate = successCount.toDouble() / lines.size
        assertTrue(successRate >= 0.8, "Success rate too low: ${(successRate * 100).toInt()}%")
    }

    // DISABLED - This test is for research purposes only and should not run in CI
    @Disabled
    @Test
    fun testStreamLogsToChatMessageMapping() {

        val streamLogsDir =
            File(System.getProperty("user.home"), ".gromozeka/streamlogs/Users-lewik-code-gromozeka-dev")

        if (!streamLogsDir.exists()) {
            println("Stream logs directory not found")
            return
        }

        val files = streamLogsDir.listFiles()
        if (files == null || files.isEmpty()) {
            println("No files found in stream logs directory")
            return
        }

        val jsonlFiles = files.filter { it.name.endsWith(".jsonl") }
        if (jsonlFiles.isEmpty()) {
            println("No .jsonl files found")
            return
        }

        val newestFile = jsonlFiles.maxByOrNull { it.lastModified() }!!
        println("Testing mapping for file: ${newestFile.name}")

        val lines = newestFile.readLines().filter { it.trim().isNotEmpty() }
        var deserializeSuccess = 0
        var mappingSuccess = 0

        for ((index, line) in lines.withIndex()) {
            try {
                val streamMessage = json.decodeFromString<StreamJsonLine>(line.trim())
                deserializeSuccess++

                val chatMessage = mapper.mapToChatMessage(streamMessage)
                mappingSuccess++

                assertNotNull(chatMessage)
                assertNotNull(chatMessage.uuid)
                assertTrue(chatMessage.content.isNotEmpty())

                println("Line $index: ${streamMessage::class.simpleName} -> ${chatMessage.role}")

            } catch (e: Exception) {
                println("Line $index: MAPPING ERROR - ${e.message}")
            }
        }

        println("Mapping results: $deserializeSuccess deserialized, $mappingSuccess mapped out of ${lines.size} lines")

        val mappingRate = mappingSuccess.toDouble() / lines.size
        assertTrue(mappingRate >= 0.8, "Mapping rate too low: ${(mappingRate * 100).toInt()}%")
    }

    private fun getMessageInfo(streamMessage: StreamJsonLine): String {
        return when (streamMessage) {
            is StreamJsonLine.System -> "subtype=${streamMessage.subtype}"
            is StreamJsonLine.User -> "sessionId=${streamMessage.sessionId}"
            is StreamJsonLine.Assistant -> "sessionId=${streamMessage.sessionId}"
            is StreamJsonLine.Result -> "subtype=${streamMessage.subtype}, error=${streamMessage.isError}"
            is StreamJsonLine.ControlRequest -> "control_request"
            is StreamJsonLine.ControlResponse -> "control_response"
        }
    }
}