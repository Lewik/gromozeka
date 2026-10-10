package com.gromozeka.presentation.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalTestApi::class)
class CopyableMarkdownBlockTest {
    @Test
    fun parsesOnlySupportedDirectiveAndConstrainedAttributes() {
        assertEquals(
            CopyableMarkdownBlockSpec(
                label = "Run migration",
                icon = CopyableMarkdownBlockSpec.Icon.TERMINAL,
                language = "bash",
            ),
            parseCopyableMarkdownBlockInfo(
                """gromozeka-copy label="Run migration" icon="terminal" language="bash" onclick="ignored""""
            ),
        )
        assertNull(parseCopyableMarkdownBlockInfo("kotlin"))
        assertEquals(
            CopyableMarkdownBlockSpec.Icon.NONE,
            parseCopyableMarkdownBlockInfo("""gromozeka-copy icon="script"""")?.icon,
        )
        assertNull(
            parseCopyableMarkdownBlockInfo("""gromozeka-copy language="bash<script>"""")?.language
        )
    }

    @Test
    fun desktopBlockCopiesOnlyFenceBody() {
        verifyCopyableBlock(width = 1280, height = 800)
    }

    @Test
    fun compactBlockCopiesOnlyFenceBody() {
        verifyCopyableBlock(width = 390, height = 844)
    }

    @Test
    fun failedHighlightingStillCopiesExactFenceBody() {
        verifyCopyableBlock(width = 390, height = 844, failHighlighting = true)
    }

    @Suppress("DEPRECATION")
    private fun verifyCopyableBlock(width: Int, height: Int, failHighlighting: Boolean = false) = runDesktopComposeUiTest(
        width = width,
        height = height,
    ) {
        val clipboardManager = TestClipboardManager()
        setContent {
            val highlighter = if (failHighlighting) CodeHighlightBoundary { _, _, _ ->
                throw IllegalStateException("synthetic highlighter failure")
            } else LocalCodeHighlightBoundary.current
            CompositionLocalProvider(LocalClipboardManager provides clipboardManager,
                LocalInspectionMode provides true, LocalCodeHighlightBoundary provides highlighter) {
                MaterialTheme {
                    GromozekaMarkdown(
                        content = """
                            ```gromozeka-copy label="Run migration" icon="terminal" language="bash"
                            ./gradlew migrate
                            ```
                        """.trimIndent(),
                    )
                }
            }
        }

        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithTag(UiTestTag.CopyableMarkdownBlock.value).fetchSemanticsNodes().size == 1
        }
        onNodeWithTag(UiTestTag.CopyableMarkdownBlock.value).assertIsDisplayed()
        onNodeWithTag(UiTestTag.CopyableMarkdownButton.value).performClick()
        onNodeWithTag(UiTestTag.CopyableMarkdownButton.value).assertContentDescriptionEquals("Copied")

        var copiedText: String? = null
        runOnIdle {
            copiedText = clipboardManager.getText()?.text
        }
        assertEquals("./gradlew migrate", copiedText)
    }

    @Suppress("DEPRECATION")
    private class TestClipboardManager : ClipboardManager {
        private var text: AnnotatedString? = null

        override fun setText(annotatedString: AnnotatedString) {
            text = annotatedString
        }

        override fun getText(): AnnotatedString? = text
    }
}
