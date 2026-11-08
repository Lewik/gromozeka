package com.gromozeka.presentation.ui.session

import com.gromozeka.domain.model.ArtifactLimits
import com.gromozeka.presentation.services.BrowserAttachmentAcquisitionController
import com.gromozeka.presentation.services.translation.LocalizedTextException
import kotlinx.coroutines.test.runTest
import kotlin.JsFun
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BrowserComposerAttachmentTest {
    @Test
    fun pasteRequiresComposerFocusAndDoesNotConsumeText() {
        val state = createComposerAttachmentRegion()
        val received = mutableListOf<Int>()
        installComposerAttachmentEvents(state) { received += offeredFileCount(it) }
        try {
            assertFalse(dispatchAttachmentPaste(withFiles = true))
            assertTrue(received.isEmpty())
            setComposerAttachmentFocus(state, true)
            assertFalse(dispatchAttachmentPaste(withFiles = false))
            assertTrue(received.isEmpty())
            assertTrue(dispatchAttachmentPaste(withFiles = true))
            assertEquals(listOf(1), received)
            setComposerAttachmentFocus(state, false)
            assertFalse(dispatchAttachmentPaste(withFiles = true))
            assertEquals(listOf(1), received)
        } finally { removeComposerAttachmentEvents(state) }
        assertFalse(dispatchAttachmentPaste(withFiles = true))
    }

    @Test
    fun dropRequiresComposerBoundsAndDisposalRemovesItsListener() {
        val state = createComposerAttachmentRegion()
        var received = 0
        installComposerAttachmentEvents(state) { received++ }
        try {
            assertFalse(dispatchAttachmentDrop(30f, 40f))
            setComposerAttachmentBounds(state, 10f, 20f, 110f, 80f)
            assertFalse(dispatchAttachmentDrop(5f, 40f))
            assertFalse(dispatchAttachmentDrop(30f, 100f))
            assertTrue(dispatchAttachmentDrop(30f, 40f))
            assertEquals(1, received)
        } finally { removeComposerAttachmentEvents(state) }
        assertFalse(dispatchAttachmentDrop(30f, 40f))
        assertEquals(1, received)
    }

    @Test
    fun browserFileReaderPreservesBinaryPayloadsNamesAndMultipleFiles() = runTest {
        val controller = BrowserAttachmentAcquisitionController()
        val uploads = controller.prepareExternalFiles(binaryFiles())()
        assertEquals(listOf("Файл #1.bin", "second.txt"), uploads.map { it.fileName })
        assertContentEquals(byteArrayOf(0, -1, 65, 10), uploads[0].content)
        assertEquals("text/plain", uploads[1].mediaType)
        assertEquals("second", uploads[1].content.decodeToString())
    }

    @Test
    fun excessiveFileCountAndSizesFailBeforeReadingData() = runTest {
        val controller = BrowserAttachmentAcquisitionController()
        for (files in listOf(
            filesWithReportedSizes(1, ArtifactLimits.MAX_FILE_BYTES + 1),
            filesWithReportedSizes(ArtifactLimits.MAX_ARTIFACTS_PER_MESSAGE + 1, 1),
            filesWithReportedSizes(3, 20 * 1024 * 1024),
        )) {
            assertFailsWith<LocalizedTextException> { controller.prepareExternalFiles(files)() }
        }
    }

    @Test
    fun nativeReaderExceptionsRemainStructuredAttachmentFailures() = runTest {
        val previous = replaceFileReaderWithFailure()
        try {
            val controller = BrowserAttachmentAcquisitionController()
            assertFailsWith<LocalizedTextException> { controller.prepareExternalFiles(binaryFiles())() }
        } finally { restoreFileReader(previous) }
    }
}

@JsFun("files => files.length")
private external fun offeredFileCount(files: JsAny): Int

@JsFun(
    """
    withFiles => {
        const transfer = new DataTransfer();
        if (withFiles) transfer.items.add(new File([new Uint8Array([1, 2, 3])], "clipboard.bin"));
        else transfer.setData("text/plain", "ordinary text");
        const event = new ClipboardEvent("paste", { bubbles: true, cancelable: true, clipboardData: transfer });
        document.dispatchEvent(event);
        return event.defaultPrevented;
    }
    """
)
private external fun dispatchAttachmentPaste(withFiles: Boolean): Boolean

@JsFun(
    """
    (x, y) => {
        const transfer = new DataTransfer();
        transfer.items.add(new File([new Uint8Array([1, 2, 3])], "dropped.bin"));
        const event = new DragEvent("drop", { bubbles: true, cancelable: true, clientX: x, clientY: y, dataTransfer: transfer });
        document.dispatchEvent(event);
        return event.defaultPrevented;
    }
    """
)
private external fun dispatchAttachmentDrop(x: Float, y: Float): Boolean

@JsFun(
    """
    () => [new File([new Uint8Array([0, 255, 65, 10])], "Файл #1.bin", {type:"application/octet-stream"}),
        new File(["second"], "second.txt", {type:"text/plain"})]
    """
)
private external fun binaryFiles(): JsAny

@JsFun("(count, size) => Array.from({length:count}, (_, index) => ({ name:'large-' + index, size }))")
private external fun filesWithReportedSizes(count: Int, size: Int): JsAny

@JsFun(
    """
    () => {
        const previous = globalThis.FileReader;
        globalThis.FileReader = class {
            readAsDataURL() { throw new DOMException("synthetic reader failure", "QuotaExceededError"); }
        };
        return previous;
    }
    """
)
private external fun replaceFileReaderWithFailure(): JsAny

@JsFun("previous => { globalThis.FileReader = previous; }")
private external fun restoreFileReader(previous: JsAny)
