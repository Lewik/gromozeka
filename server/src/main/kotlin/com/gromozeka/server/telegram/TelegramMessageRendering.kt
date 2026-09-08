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

    fun status(invocation: TelegramInvocation, deliveryProblem: Boolean): String = presentation(invocation, deliveryProblem).first()

    fun presentation(invocation: TelegramInvocation, deliveryProblem: Boolean = false): List<String> {
        val answer = invocation.responseTexts.joinToString("\n\n")
        val status = if (!invocation.completed || invocation.failed || invocation.stopRequested || answer.isBlank()) {
            "${invocation.agentName.ifBlank { "Gromozeka" }} · ${telegramText(locale, invocation.statusKey)}"
        } else ""
        val prefix = if (invocation.binding.routes.size > 1 && status.isBlank()) "${invocation.agentName}\n" else ""
        val footer = listOf(status, if (deliveryProblem) "⚠️ Gromozeka" else "").filter(String::isNotBlank).joinToString(" · ")
        val activity = invocation.activity.joinToString("\n\n").takeLast(2800).dropWhile { it.isLowSurrogate() }
        return TelegramMarkdown.render(answer, prefix, footer, activity)
    }

    fun messageTexts(message: Conversation.Message): List<String> = message.content.mapNotNull { item ->
        val text = when (item) {
            is Conversation.Message.ContentItem.UserMessage -> item.text
            is Conversation.Message.ContentItem.AssistantMessage -> item.structured.fullText
            else -> ""
        }
        text.takeIf(String::isNotBlank)
    }
}

internal object TelegramMarkdown {
    private val parser = Parser.builder().build()

    fun render(markdown: String, prefix: String = "", footer: String = "", expandableText: String = ""): List<String> {
        if (markdown.isBlank() && footer.isBlank() && expandableText.isBlank()) return emptyList()
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
        if (footer.isNotBlank()) output.text(footer)
        if (expandableText.isNotBlank()) {
            output.text("\n")
            output.open("blockquote", " expandable")
            output.text(expandableText)
            output.close("blockquote")
        }
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
