package com.gromozeka.server.telegram

import com.gromozeka.domain.model.*
import kotlin.test.*
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

class TelegramRenderingTest {
    @Test fun `markdown uses only Telegram tags and escapes user supplied HTML`() {
        val chunks = TelegramMarkdown.render("# Header\n\n**bold** and *italic* with `code`\nnext line\n\n<script>bad</script>\n\n[link](https://example.com/?a=1&b=2)\n\n1. One\n2. Two")
        val text = chunks.joinToString("")
        assertTrue(text.contains("<b>Header</b>")); assertTrue(text.contains("<b>bold</b>"))
        assertTrue(text.contains("<i>italic</i>")); assertTrue(text.contains("<code>code</code>"))
        assertTrue(text.contains("&lt;script&gt;")); assertFalse(text.contains("<script>"))
        assertTrue(text.contains("1. One")); assertTrue(text.contains("2. Two"))
        assertFalse(text.contains("<p>"))
        chunks.forEach(::assertValidHtml)
    }

    @Test fun `long code and Unicode are chunked without corrupting tags entities or surrogate pairs`() {
        val body = "Ж😀 <>&\n".repeat(2000)
        val chunks = TelegramMarkdown.render("```kotlin\n$body```", "Agent\n")
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 3900 })
        chunks.forEach(::assertValidHtml)
        val plain = chunks.joinToString("") { parse(it).documentElement.textContent }
        assertEquals("Agent\n$body\n", plain)
        assertTrue(chunks.all { it.contains("language-kotlin") })
    }

    @Test fun `unsafe links are plain text and nested quotes are flattened`() {
        val html = TelegramMarkdown.render("[unsafe](javascript:alert)\n\n> Outer\n> > Inner").single()
        assertFalse(html.contains("href")); assertEquals(1, Regex("<blockquote>").findAll(html).count())
        assertValidHtml(html)
    }

    @Test fun `opaque thinking never becomes a fake activity or leaks its payload`() {
        val renderer = TelegramMessageRendering("en")
        val opaque = Conversation.Message.ContentItem.Thinking("SECRET", kind = Conversation.Message.ContentItem.Thinking.Kind.REDACTED)
        assertNull(renderer.activity(opaque))
        assertTrue(renderer.activity(Conversation.Message.ContentItem.Thinking("Actual explanation"))!!.contains("Actual explanation"))
    }

    private fun parse(html: String) = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(InputSource(StringReader("<root>$html</root>")))
    private fun assertValidHtml(html: String) { assertTrue(html.length <= 4096); parse(html) }
}
