package com.gromozeka.server.telegram

import com.gromozeka.domain.model.*
import com.gromozeka.shared.localization.*
import org.commonmark.node.*
import org.commonmark.parser.Parser

internal fun telegramText(locale: String, key: String): String =
    TranslationCatalog(BundledTranslations.get(BundledTranslations.matchLocale(locale))).text(key)

internal fun telegramEscape(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

internal class TelegramMessageRendering(private val locale: String) {
    fun activity(item: Conversation.Message.ContentItem): String? = when (item) {
        is Conversation.Message.ContentItem.ToolCall -> "🛠 ${ToolDisplayText.name(item.call.name) { telegramText(locale, it) }}\n${item.call.input.toString().take(1200)}"
        is Conversation.Message.ContentItem.ToolResult -> "${if (item.isError) "❌" else "✅"} ${ToolDisplayText.name(item.toolName) { telegramText(locale, it) }}"
        is Conversation.Message.ContentItem.Thinking -> if (item.kind == Conversation.Message.ContentItem.Thinking.Kind.REDACTED || item.thinking.isBlank()) null
            else "💭 ${telegramText(locale, "runtime.thinkingLabel")}\n${item.thinking.take(2200)}"
        else -> null
    }

    fun status(invocation: TelegramInvocation, deliveryProblem: Boolean): String = buildString {
        append(telegramEscape(invocation.agentName.ifBlank { "Gromozeka" }))
        append(" · "); append(telegramEscape(telegramText(locale, invocation.statusKey)))
        if (deliveryProblem) append(" · ⚠️ Gromozeka")
        if (invocation.activity.isNotEmpty()) {
            append("\n<blockquote expandable>")
            var remaining = 2800
            val activity = invocation.activity.joinToString("\n\n").takeLast(2800).dropWhile { it.isLowSurrogate() }
            for (codePoint in activity.codePoints().toArray()) {
                val escaped = telegramEscape(String(Character.toChars(codePoint)))
                if (escaped.length > remaining) break
                append(escaped); remaining -= escaped.length
            }
            append("</blockquote>")
        }
    }

    fun message(message: Conversation.Message, agentName: String): List<String> = message.content.flatMap { item ->
        val text = when (item) {
            is Conversation.Message.ContentItem.UserMessage -> item.text
            is Conversation.Message.ContentItem.AssistantMessage -> item.structured.fullText
            else -> ""
        }
        if (text.isBlank()) emptyList() else TelegramMarkdown.render(text, "$agentName\n")
    }
}

internal object TelegramMarkdown {
    private val parser = Parser.builder().build()

    fun render(markdown: String, prefix: String = ""): List<String> {
        if (markdown.isBlank()) return emptyList()
        val output = HtmlChunks(prefix)
        fun children(node: Node, depth: Int, quoted: Boolean, render: (Node, Int, Boolean) -> Unit) {
            var child = node.firstChild
            while (child != null) { render(child, depth, quoted); child = child.next }
        }
        fun render(node: Node, depth: Int = 0, quoted: Boolean = false) {
            fun content() = children(node, depth, quoted, ::render)
            fun tag(name: String) { output.open(name); content(); output.close(name) }
            when (node) {
                is Text -> output.text(node.literal)
                is SoftLineBreak, is HardLineBreak -> output.text("\n")
                is StrongEmphasis -> tag("b")
                is Emphasis -> tag("i")
                is Code -> { output.open("code"); output.text(node.literal); output.close("code") }
                is FencedCodeBlock -> {
                    output.open("pre")
                    val language = node.info.orEmpty().substringBefore(' ').takeIf { it.matches(Regex("[A-Za-z0-9_+.-]{1,32}")) }
                    output.open("code", language?.let { " class=\"language-$it\"" }.orEmpty())
                    output.text(node.literal); output.close("code"); output.close("pre"); output.text("\n")
                }
                is IndentedCodeBlock -> { output.open("pre"); output.text(node.literal); output.close("pre"); output.text("\n") }
                is Heading -> { tag("b"); output.text("\n\n") }
                is Paragraph -> { content(); output.text(if (node.parent is ListItem) "\n" else "\n\n") }
                is ListItem -> {
                    val ordered = node.parent as? OrderedList
                    val number = ordered?.let { parent ->
                        var index = parent.markerStartNumber ?: 1
                        var sibling = parent.firstChild
                        while (sibling != null && sibling != node) { index++; sibling = sibling.next }; index
                    }
                    output.text("  ".repeat(depth.coerceAtMost(8)) + (number?.let { "$it. " } ?: "• "))
                    children(node, depth + 1, quoted, ::render)
                }
                is BlockQuote -> {
                    if (!quoted) output.open("blockquote")
                    children(node, depth, true, ::render)
                    if (!quoted) output.close("blockquote")
                    output.text("\n")
                }
                is Link -> {
                    val safe = node.destination.takeIf { it.length <= 1024 && it.matches(Regex("(?i)(https?://|mailto:).+")) }
                    if (safe == null) content() else {
                        output.open("a", " href=\"${telegramEscape(safe)}\""); content(); output.close("a")
                    }
                }
                is Image -> { content(); output.text(" (${node.destination.take(1024)})") }
                is HtmlInline -> output.text(node.literal)
                is HtmlBlock -> { output.text(node.literal); output.text("\n") }
                is ThematicBreak -> output.text("───\n")
                else -> content()
            }
        }
        render(parser.parse(markdown))
        return output.finish()
    }

    private class HtmlChunks(prefix: String) {
        private val chunks = mutableListOf<String>()
        private val tags = mutableListOf<Pair<String, String>>()
        private var buffer = StringBuilder(telegramEscape(prefix))
        private var visible = prefix.isNotEmpty()
        fun open(name: String, attributes: String = "") {
            val opening = "<$name$attributes>"
            if (buffer.length + closingLength() + opening.length + name.length + 4 > 3900) flush()
            tags += name to opening; buffer.append(opening)
        }
        fun close(name: String) { check(tags.removeAt(tags.lastIndex).first == name); buffer.append("</$name>") }
        fun text(value: String) {
            var index = 0
            while (index < value.length) {
                val width = if (value[index].isHighSurrogate() && index + 1 < value.length && value[index + 1].isLowSurrogate()) 2 else 1
                val escaped = telegramEscape(value.substring(index, index + width))
                if (buffer.length + closingLength() + escaped.length > 3900) flush()
                buffer.append(escaped); visible = true; index += width
            }
        }
        private fun closingLength(): Int = tags.sumOf { it.first.length + 3 }
        private fun flush() {
            if (visible) chunks += buffer.toString() + tags.asReversed().joinToString("") { "</${it.first}>" }
            buffer = StringBuilder(tags.joinToString("") { it.second }); visible = false
        }
        fun finish(): List<String> { check(tags.isEmpty()); flush(); return chunks }
    }
}
