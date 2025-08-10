package com.gromozeka.bot.services

import com.gromozeka.bot.settings.ResponseFormat
import java.io.InputStream

/**
 * Loads system prompts from resources based on response format
 */
object SystemPromptLoader {
    
    private const val PROMPTS_PATH = "/prompts/"
    
    /**
     * Get system prompt for specified format
     */
    fun loadPrompt(format: ResponseFormat): String {
        // Load base prompt
        val basePrompt = loadResourceFile("${PROMPTS_PATH}base-prompt.md")
            ?: "You're Gromozeka - a multi-armed AI buddy. Be direct, casual, and real with the user."
        
        // Load format-specific instructions
        val formatFilename = when (format) {
            ResponseFormat.JSON -> "json-format.md"
            ResponseFormat.XML_STRUCTURED -> "xml-structured.md"
            ResponseFormat.XML_INLINE -> "xml-inline.md"
            ResponseFormat.PLAIN_TEXT -> "plain-text.md"
        }
        
        val formatPrompt = loadResourceFile("$PROMPTS_PATH$formatFilename") ?: ""
        
        // Combine base + format
        return if (formatPrompt.isNotEmpty()) {
            "$basePrompt\n\n$formatPrompt"
        } else {
            basePrompt
        }
    }

    
    /**
     * Load prompt file from resources
     */
    private fun loadResourceFile(path: String): String? {
        return try {
            val inputStream: InputStream? = this::class.java.getResourceAsStream(path)
            inputStream?.bufferedReader()?.use { it.readText() }
        } catch (e: Exception) {
            println("[SystemPromptLoader] Failed to load prompt from $path: ${e.message}")
            null
        }
    }
}