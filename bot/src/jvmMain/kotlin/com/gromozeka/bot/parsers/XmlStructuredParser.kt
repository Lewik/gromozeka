package com.gromozeka.bot.parsers

import com.gromozeka.shared.domain.conversation.ConversationTree
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory


/**
 * Parser for structured XML format responses
 * Format defined in: /resources/prompts/xml-structured.md
 */
class XmlStructuredParser : ResponseParser {

    private val documentBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder()

    override fun parse(text: String): ConversationTree.Message.StructuredText {
        val trimmedText = text.trim()

        if (!trimmedText.startsWith("<response>") || !trimmedText.endsWith("</response>")) {
            throw IllegalArgumentException("Text is not XML structured format")
        }

        val doc = documentBuilder.parse(InputSource(StringReader(trimmedText)))
        doc.documentElement.normalize()

        val root = doc.documentElement
        if (root.tagName != "response") {
            throw IllegalArgumentException("Root element must be 'response', got: ${root.tagName}")
        }

        val visualNodes = root.getElementsByTagName("visual")
        val fullText = if (visualNodes.length > 0) {
            visualNodes.item(0).textContent?.trim() ?: ""
        } else {
            throw IllegalArgumentException("Missing required <visual> tag")
        }

        val voiceNodes = root.getElementsByTagName("voice")
        var ttsText: String? = null
        var voiceTone: String? = null

        if (voiceNodes.length > 0) {
            val voiceNode = voiceNodes.item(0)
            ttsText = voiceNode.textContent?.trim()
            voiceTone = voiceNode.attributes?.getNamedItem("tone")?.nodeValue
        }

        return ConversationTree.Message.StructuredText(
            fullText = fullText,
            ttsText = ttsText,
            voiceTone = voiceTone
        )
    }
}