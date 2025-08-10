package com.gromozeka.bot.parsers

import com.gromozeka.bot.settings.ResponseFormat

/**
 * Factory for creating appropriate response parser based on format setting
 */
object ResponseParserFactory {
    
    private val jsonParser = JsonResponseParser()
    private val xmlStructuredParser = XmlStructuredParser()
    private val xmlInlineParser = XmlInlineParser()
    private val plainTextParser = PlainTextParser()
    
    /**
     * Get parser for specified format
     */
    fun getParser(format: ResponseFormat): ResponseParser {
        return when (format) {
            ResponseFormat.JSON -> jsonParser
            ResponseFormat.XML_STRUCTURED -> xmlStructuredParser
            ResponseFormat.XML_INLINE -> xmlInlineParser
            ResponseFormat.PLAIN_TEXT -> plainTextParser
        }
    }
    
    /**
     * Try all parsers in order of likelihood until one succeeds
     * Used as fallback when primary parser fails
     */
    fun tryAllParsers(text: String): com.gromozeka.shared.domain.message.ChatMessage.StructuredText? {
        // Try JSON first (most common)
        jsonParser.parse(text)?.let { return it }
        
        // Try XML structured
        xmlStructuredParser.parse(text)?.let { return it }
        
        // Try XML inline
        xmlInlineParser.parse(text)?.let { return it }
        
        // Finally, plain text always succeeds if text is not empty
        return plainTextParser.parse(text)
    }
}