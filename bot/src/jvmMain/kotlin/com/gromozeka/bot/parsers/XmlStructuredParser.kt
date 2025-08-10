package com.gromozeka.bot.parsers

import com.gromozeka.shared.domain.message.ChatMessage
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.xml.sax.InputSource
import java.io.StringReader

/**
 * Parser for structured XML format responses
 * Expected format:
 * <response>
 *   <visual>Full markdown content</visual>
 *   <voice tone="casual">TTS text</voice>
 * </response>
 */
class XmlStructuredParser : ResponseParser {
    
    private val documentBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
    
    override fun parse(text: String): ChatMessage.StructuredText? {
        return try {
            val trimmedText = text.trim()
            
            // Check if it looks like XML
            if (!trimmedText.startsWith("<response>") || !trimmedText.endsWith("</response>")) {
                println("[XmlStructuredParser] Text doesn't look like structured XML, skipping")
                return null
            }
            
            val doc = documentBuilder.parse(InputSource(StringReader(trimmedText)))
            doc.documentElement.normalize()
            
            val root = doc.documentElement
            if (root.tagName != "response") {
                println("[XmlStructuredParser] Root element is not 'response': ${root.tagName}")
                return null
            }
            
            // Extract visual content (required)
            val visualNodes = root.getElementsByTagName("visual")
            val fullText = if (visualNodes.length > 0) {
                visualNodes.item(0).textContent?.trim() ?: ""
            } else {
                println("[XmlStructuredParser] No <visual> tag found")
                return null
            }
            
            // Extract voice content (optional)
            val voiceNodes = root.getElementsByTagName("voice")
            var ttsText: String? = null
            var voiceTone: String? = null
            
            if (voiceNodes.length > 0) {
                val voiceNode = voiceNodes.item(0)
                ttsText = voiceNode.textContent?.trim()
                voiceTone = voiceNode.attributes?.getNamedItem("tone")?.nodeValue
            }
            
            ChatMessage.StructuredText(
                fullText = fullText,
                ttsText = ttsText,
                voiceTone = voiceTone
            )
            
        } catch (e: Exception) {
            println("[XmlStructuredParser] Failed to parse XML: ${e.message}")
            println("  Text: ${text.take(100)}${if (text.length > 100) "..." else ""}")
            null
        }
    }
}