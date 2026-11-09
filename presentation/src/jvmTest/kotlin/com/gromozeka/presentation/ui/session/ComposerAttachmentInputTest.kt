package com.gromozeka.presentation.ui.session

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.asAwtTransferable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.gromozeka.domain.model.Artifact
import com.gromozeka.domain.model.ArtifactUpload
import com.gromozeka.presentation.services.AttachmentAcquisition
import com.gromozeka.presentation.services.AttachmentAcquisitionCapabilities
import com.gromozeka.presentation.services.AttachmentAcquisitionController
import com.gromozeka.presentation.ui.CompactTextField
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ComposerAttachmentInputTest {
    @Test
    fun keyboardPastePreservesTextSelectionAndDoesNotInterceptOtherFields() = runComposeUiTest {
        val input = mutableStateOf(TextFieldValue("draft"))
        val outside = mutableStateOf(TextFieldValue(""))
        val received = mutableListOf<ArtifactUpload>()
        val controller = TestController()
        setContent {
            val scope = rememberCoroutineScope()
            val receiver = remember { ComposerAttachmentReceiver(controller) { acquire ->
                scope.launch(start = CoroutineStart.UNDISPATCHED) { received += acquire() }
            } }
            CompositionLocalProvider(LocalClipboard provides remember { MemoryClipboard("insert") }) {
                MaterialTheme {
                    Column {
                        ComposerAttachmentContextMenu(receiver, "Paste attachments") {
                            CompactTextField(input.value, { input.value = it }, Modifier.width(300.dp)
                                .composerAttachmentInput(receiver, "Paste attachments").testTag("composer"))
                        }
                        CompactTextField(outside.value, { outside.value = it }, Modifier.width(300.dp).testTag("outside"))
                    }
                }
            }
        }
        val composer = onNodeWithTag("composer")
        composer.performClick().performTextInputSelection(TextRange(2))
        composer.assertIsFocused().paste()
        // Compose reads Transferable text on Dispatchers.IO, outside UI idleness.
        waitUntil(timeoutMillis = 5_000) { input.value.text == "drinsertaft" }
        runOnIdle {
            assertEquals("drinsertaft", input.value.text)
            assertEquals(TextRange(8), input.value.selection)
            assertTrue(received.isEmpty())
            controller.prepared = { listOf(upload("image.png")) }
        }
        onNodeWithTag("outside").performClick().assertIsFocused().paste()
        waitUntil(timeoutMillis = 5_000) { outside.value.text == "insert" }
        runOnIdle {
            assertEquals("insert", outside.value.text)
            assertTrue(received.isEmpty())
            assertEquals(1, controller.preparations)
        }
        composer.performClick().assertIsFocused().paste()
        runOnIdle {
            assertEquals(listOf("image.png"), received.map { it.fileName })
            assertEquals("drinsertaft", input.value.text)
        }
    }

    @Test
    fun textPasteCanFinishAfterTheUiBecomesIdle() = runComposeUiTest {
        val readStarted = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val text = StringSelection("insert")
        val transferable = object : Transferable by text {
            override fun getTransferData(flavor: DataFlavor): Any {
                readStarted.countDown()
                check(releaseRead.await(5, TimeUnit.SECONDS)) { "Test did not release the clipboard read" }
                return text.getTransferData(flavor)
            }
        }
        val clipboard = MemoryClipboard(ClipEntry(transferable))
        val input = mutableStateOf(TextFieldValue("draft"))
        val controller = TestController()
        val received = mutableListOf<ArtifactUpload>()
        setContent {
            val scope = rememberCoroutineScope()
            val receiver = remember { ComposerAttachmentReceiver(controller) { acquire ->
                scope.launch(start = CoroutineStart.UNDISPATCHED) { received += acquire() }
            } }
            CompositionLocalProvider(LocalClipboard provides clipboard) {
                MaterialTheme {
                    CompactTextField(input.value, { input.value = it }, Modifier.width(300.dp)
                        .composerAttachmentInput(receiver, "Paste attachments").testTag("composer"))
                }
            }
        }
        try {
            val composer = onNodeWithTag("composer")
            composer.performClick().performTextInputSelection(TextRange(5))
            composer.assertIsFocused().paste()
            assertTrue(readStarted.await(5, TimeUnit.SECONDS))
            runOnIdle {
                assertEquals("draft", input.value.text)
                assertEquals(1, controller.preparations)
                assertTrue(received.isEmpty())
            }
        } finally {
            releaseRead.countDown()
        }
        waitUntil(timeoutMillis = 5_000) { input.value.text == "draftinsert" }
        runOnIdle {
            assertEquals("draftinsert", input.value.text)
            assertEquals(TextRange(11), input.value.selection)
            assertTrue(received.isEmpty())
        }
    }

    @Test
    fun contextMenuDoesNotAcquireOnInspectionAndOffersExplicitAttachmentPaste() = runComposeUiTest {
        val controller = TestController().apply { prepared = { listOf(upload("menu.png")) } }
        val received = mutableListOf<ArtifactUpload>()
        val input = mutableStateOf(TextFieldValue("draft"))
        setContent {
            val scope = rememberCoroutineScope()
            val receiver = remember { ComposerAttachmentReceiver(controller) { acquire ->
                scope.launch(start = CoroutineStart.UNDISPATCHED) { received += acquire() }
            } }
            CompositionLocalProvider(LocalClipboard provides remember { MemoryClipboard("") }) {
                MaterialTheme {
                    ComposerAttachmentContextMenu(receiver, "Paste attachments") {
                        CompactTextField(input.value, { input.value = it }, Modifier.width(300.dp)
                            .composerAttachmentInput(receiver, "Paste attachments").testTag("composer"))
                    }
                }
            }
        }
        onNodeWithTag("composer").performMouseInput { click(button = MouseButton.Secondary) }
        onNodeWithText("Paste attachments").assertIsDisplayed()
        runOnIdle { assertEquals(0, controller.preparations); assertTrue(received.isEmpty()) }
        onNodeWithText("Paste attachments").performClick()
        runOnIdle {
            assertEquals(1, controller.preparations)
            assertEquals(listOf("menu.png"), received.map { it.fileName })
            assertEquals("draft", input.value.text)
        }
    }

    @Test
    fun delayedAcquisitionStaysWithItsOriginalComposerAfterNavigation() = runComposeUiTest {
        val first = CompletableDeferred<List<ArtifactUpload>>()
        val second = CompletableDeferred<List<ArtifactUpload>>()
        val controller = TestController().apply { prepared = { first.await() } }
        val owner = mutableStateOf("first")
        val received = mutableListOf<Pair<String, String>>()
        val input = mutableStateOf(TextFieldValue("unchanged draft"))
        setContent {
            val scope = rememberCoroutineScope()
            val target = owner.value
            val receiver = remember(target) { ComposerAttachmentReceiver(controller) { acquire ->
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    received += acquire().map { target to it.fileName }
                }
            } }
            CompositionLocalProvider(LocalClipboard provides remember { MemoryClipboard("") }) {
                MaterialTheme {
                    ComposerAttachmentContextMenu(receiver, "Paste attachments") {
                        CompactTextField(input.value, { input.value = it }, Modifier.width(300.dp)
                            .composerAttachmentInput(receiver, "Paste attachments").testTag("composer"))
                    }
                }
            }
        }
        onNodeWithTag("composer").performClick().paste()
        runOnIdle { owner.value = "second"; controller.prepared = { second.await() } }
        onNodeWithTag("composer").paste()
        runOnIdle { second.complete(listOf(upload("second.png"))) }
        runOnIdle { first.complete(listOf(upload("first.png"))) }
        runOnIdle {
            assertEquals(listOf("second" to "second.png", "first" to "first.png"), received)
            assertEquals("unchanged draft", input.value.text)
        }
    }

    private fun SemanticsNodeInteraction.paste() = performKeyInput {
        val modifier = if (System.getProperty("os.name").contains("mac", ignoreCase = true)) Key.MetaLeft else Key.CtrlLeft
        keyDown(modifier); pressKey(Key.V); keyUp(modifier)
    }

    private class TestController : AttachmentAcquisitionController {
        override val capabilities = AttachmentAcquisitionCapabilities(false, false, clipboardPaste = true)
        var prepared: AttachmentAcquisition? = null
        var preparations = 0
        override fun prepareClipboardAttachments(): AttachmentAcquisition? { preparations++; return prepared }
        override suspend fun pickAttachments(): List<ArtifactUpload> = emptyList()
        override suspend fun captureScreenshot(): ArtifactUpload? = null
    }

    private class MemoryClipboard(private var entry: ClipEntry?) : Clipboard {
        constructor(text: String) : this(ClipEntry(StringSelection(text)))
        override val nativeClipboard = java.awt.datatransfer.Clipboard("composer-input-test")
        override suspend fun getClipEntry(): ClipEntry? = entry
        override suspend fun setClipEntry(clipEntry: ClipEntry?) {
            entry = clipEntry
            nativeClipboard.setContents(clipEntry?.asAwtTransferable ?: StringSelection(""), null)
        }
    }

    private fun upload(name: String) = ArtifactUpload(name, "image/png", byteArrayOf(1, 2, 3), Artifact.Purpose.USER_ATTACHMENT)
}
