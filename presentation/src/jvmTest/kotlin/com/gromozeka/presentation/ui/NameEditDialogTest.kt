package com.gromozeka.presentation.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import org.jetbrains.skia.Image

class NameEditDialogTest {
    @Test
    fun renameUsesAModalDialogOutsideTheTabRowBounds() = runComposeUiTest {
        showDialog()

        onAllNodes(isDialog(), useUnmergedTree = true).assertCountEquals(1)
        input().assertIsDisplayed().assertIsFocused().assert(
            SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(0, ORIGINAL.length))
        )
        save().assertIsDisplayed().assertIsNotEnabled()
    }

    @Test
    fun savesTrimmedNameOnlyOnceAndWaitsForTheServerBeforeClosing() = runComposeUiTest {
        val response = CompletableDeferred<Unit>()
        val submitted = mutableListOf<String>()
        var dismissed = false
        showDialog(onRename = { submitted += it; response.await() }, onDismiss = { dismissed = true })

        input().performTextReplacement("  Новый заголовок  ")
        save().performClick()
        waitUntil { submitted.isNotEmpty() }
        assertEquals(listOf("Новый заголовок"), submitted)
        input().assertIsNotEnabled()
        save().assertIsNotEnabled()
        cancel().assertIsNotEnabled()
        assertFalse(dismissed)

        response.complete(Unit)
        waitUntil { dismissed }
        assertEquals(1, submitted.size)
    }

    @Test
    fun failedSaveKeepsTheDialogAndDraftOpenForRetry() = runComposeUiTest {
        var attempts = 0
        var dismissed = false
        showDialog(
            onRename = { if (++attempts == 1) error("Server unavailable") },
            onDismiss = { dismissed = true },
        )

        input().performTextReplacement("Keep this draft")
        save().performClick()
        waitUntil { onAllNodesWithTag(UiTestTag.NameEditError.value, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(UiTestTag.NameEditError.value, useUnmergedTree = true).assertTextEquals("Server unavailable")
        input().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("Keep this draft")))
        assertFalse(dismissed)
        save().assertIsEnabled().performClick()
        waitUntil { dismissed }
        assertEquals(2, attempts)
    }

    @Test
    fun enterSavesAndEscapeCancelsWithoutSaving() {
        runComposeUiTest {
            val submitted = mutableListOf<String>()
            showDialog(onRename = { submitted += it })
            input().performTextReplacement("Keyboard rename")
            input().performKeyInput { pressKey(Key.Enter) }
            waitUntil { submitted.isNotEmpty() }
            assertEquals(listOf("Keyboard rename"), submitted)
        }
        runComposeUiTest {
            var saved = false
            var dismissed = false
            showDialog(onRename = { saved = true }, onDismiss = { dismissed = true })
            input().performTextReplacement("Discard this")
            input().performKeyInput { pressKey(Key.Escape) }
            waitUntil { dismissed }
            assertFalse(saved)
        }
    }

    @Test
    fun emptyNameResetsTheCustomTitleAndLengthLimitIsEnforced() = runComposeUiTest {
        val submitted = mutableListOf<String>()
        showDialog(currentName = "Old", maxLength = 5, onRename = { submitted += it })
        input().performTextReplacement("123456")
        input().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("Old")))
        save().assertIsNotEnabled()
        input().performTextReplacement("12345")
        save().assertIsEnabled()
        input().performTextReplacement("   ")
        save().performClick()
        waitUntil { submitted.isNotEmpty() }
        assertEquals(listOf(""), submitted)
    }

    @Test
    fun unchangedWhitespaceOnlyEditDoesNotSave() = runComposeUiTest {
        showDialog()
        input().performTextReplacement("  $ORIGINAL  ")
        save().assertIsNotEnabled()
    }

    @Test
    fun darkDesktopDialogHasBoundedWidth() = verifyAppearance(width = 1280, dark = true)

    @Test
    fun lightNarrowDialogFitsTheViewport() = verifyAppearance(width = 360, dark = false)

    private fun verifyAppearance(width: Int, dark: Boolean) = runDesktopComposeUiTest(width = width, height = 720) {
        showDialog(dark = dark)
        val dialog = onNodeWithTag(UiTestTag.NameEditDialog.value)
        dialog.assertIsDisplayed()
        val bounds = dialog.getUnclippedBoundsInRoot()
        assertTrue(bounds.right - bounds.left <= minOf(width, 480).dp, "Dialog is too wide: $bounds")
        input().assertIsDisplayed()
        save().assertIsDisplayed()
        cancel().assertIsDisplayed()
        val file = File("build/test-screenshots/name-edit-${if (dark) "dark" else "light"}-$width.png")
        file.parentFile.mkdirs()
        Image.makeFromBitmap(dialog.captureToImage().asSkiaBitmap()).use { image ->
            checkNotNull(image.encodeToData()).use { data -> file.writeBytes(data.bytes) }
        }
    }

    private fun ComposeUiTest.showDialog(
        currentName: String = ORIGINAL,
        maxLength: Int = 255,
        dark: Boolean = false,
        onRename: suspend (String) -> Unit = {},
        onDismiss: () -> Unit = {},
    ) {
        setContent {
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Box(Modifier.height(48.dp)) {
                    NameEditDialog(
                        isOpen = true,
                        currentName = currentName,
                        title = "Rename conversation",
                        label = "Name",
                        maxLength = maxLength,
                        onRename = onRename,
                        onDismiss = onDismiss,
                    )
                }
            }
        }
    }

    private fun ComposeUiTest.input() = onNodeWithTag(UiTestTag.NameEditInput.value)
    private fun ComposeUiTest.save() = onNodeWithTag(UiTestTag.NameEditSave.value)
    private fun ComposeUiTest.cancel() = onNodeWithTag(UiTestTag.NameEditCancel.value)

    private companion object {
        const val ORIGINAL = "Original conversation"
    }
}
