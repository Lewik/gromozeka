package com.gromozeka.presentation.ui.session

private val reasoningHeadingPrefix = Regex("^#{1,6}[ \\t]+")
private val reasoningHeadingSuffix = Regex("[ \\t]+#+$")
private val reasoningTitleWrappers = listOf("**", "__", "*", "_", "~~", "`")

internal fun reasoningSummaryTitle(text: String): String? {
    var title = text.lineSequence().map(String::trim).firstOrNull(String::isNotBlank) ?: return null
    if (reasoningHeadingPrefix.containsMatchIn(title)) {
        title = title.replaceFirst(reasoningHeadingPrefix, "").replace(reasoningHeadingSuffix, "").trim()
    }
    while (true) {
        val wrapper = reasoningTitleWrappers.firstOrNull {
            title.length >= it.length * 2 && title.startsWith(it) && title.endsWith(it)
        } ?: break
        title = title.removeSurrounding(wrapper).trim()
    }
    return title.takeIf(String::isNotBlank)
}
