package com.gromozeka.presentation.ui.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReasoningActivityTitleTest {
    @Test
    fun usesTheFirstNonblankSummaryLineWithoutInventingAnotherSummary() {
        assertEquals("Check the gateway configuration.", reasoningSummaryTitle(" \nCheck the gateway configuration.\n\nThen compare the responses."))
        assertEquals("Проверить настройки", reasoningSummaryTitle("\r\nПроверить настройки\r\n\r\nПодробности"))
        assertEquals("First line", reasoningSummaryTitle("First line\n# Later heading"))
    }

    @Test
    fun unwrapsWholeLineMarkdownHeadingsAndFormatting() {
        for (line in listOf("**Check configuration**", "__Check configuration__", "# Check configuration",
            "### **Check configuration** ###", "_**Check configuration**_", "`Check configuration`")) {
            assertEquals("Check configuration", reasoningSummaryTitle("$line\n\nDetails"), line)
        }
    }

    @Test
    fun keepsLiteralContentAndIncompleteFormattingRatherThanRewritingIt() {
        for (line in listOf("Check file_name and #42", "Compare **two** routes", "**Incomplete heading", "####### Literal", "#tag")) {
            assertEquals(line, reasoningSummaryTitle(line))
        }
        val title = "Long summary ".repeat(100)
        assertEquals(title.trim(), reasoningSummaryTitle(title))
    }

    @Test
    fun missingReadableTextHasNoTitle() {
        for (text in listOf("", " \n\t\r\n", "****", "** **", "## ** ** ##")) {
            assertNull(reasoningSummaryTitle(text), text)
        }
    }
}
