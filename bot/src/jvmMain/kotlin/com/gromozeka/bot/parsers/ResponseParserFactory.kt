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

    fun getParser(format: ResponseFormat) = when (format) {
        ResponseFormat.JSON -> jsonParser
        ResponseFormat.XML_STRUCTURED -> xmlStructuredParser
        ResponseFormat.XML_INLINE -> xmlInlineParser
        ResponseFormat.PLAIN_TEXT -> plainTextParser
    }

}