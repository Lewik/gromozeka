package com.gromozeka.infrastructure.ai.parsers

internal data class SuggestedRepliesExtraction(
    val visibleText: String,
    val suggestedReplies: List<String>,
)

internal fun extractSuggestedReplies(text: String): SuggestedRepliesExtraction {
    val matches = SUGGESTED_REPLIES_PATTERN.findAll(text).toList()
    val suggestions = matches.flatMap { container ->
        SUGGESTED_REPLY_PATTERN.findAll(container.groupValues[1])
            .map { it.groupValues[1] }
            .toList()
    }
    return SuggestedRepliesExtraction(
        visibleText = text.replace(SUGGESTED_REPLIES_PATTERN, "").trim(),
        suggestedReplies = sanitizeSuggestedReplies(suggestions),
    )
}

internal fun sanitizeSuggestedReplies(values: List<String>): List<String> = values
    .asSequence()
    .map { value ->
        value
            .map { character -> if (character.isISOControl()) ' ' else character }
            .joinToString("")
            .trim()
            .split(Regex("\\s+"))
            .filter(String::isNotEmpty)
            .joinToString(" ")
    }
    .filter { it.isNotEmpty() && it.length <= MAX_SUGGESTED_REPLY_LENGTH }
    .distinctBy(String::lowercase)
    .take(MAX_SUGGESTED_REPLIES)
    .toList()

private const val MAX_SUGGESTED_REPLIES = 4
private const val MAX_SUGGESTED_REPLY_LENGTH = 120
private val SUGGESTED_REPLIES_PATTERN = Regex(
    """<suggested_replies\s*>(.*?)</suggested_replies\s*>""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)
private val SUGGESTED_REPLY_PATTERN = Regex(
    """<reply\s*>(.*?)</reply\s*>""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)
