package com.gromozeka.presentation.services

import com.gromozeka.domain.model.Artifact
import com.gromozeka.domain.model.ArtifactLimits
import com.gromozeka.presentation.services.translation.LocalizedTextException
import com.gromozeka.presentation.services.translation.data.EnglishTranslation
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopAttachmentAcquisitionControllerTest {
    @Test
    fun ordinaryTextAndTypedFileUrlsRemainTextPaste() {
        for (text in listOf("ordinary text", "file:///not-an-offered-file", "https://example.com/image.png")) {
            val controller = controller { StringSelection(text) }
            assertNull(controller.prepareClipboardAttachments())
        }
        val uriList = transfer(uriFlavor to "https://example.com/not-a-local-file")
        assertNull(controller { uriList }.prepareClipboardAttachments())
    }

    @Test
    fun copiedImageBecomesOnePngWithoutChangingItsPixels() = runBlocking {
        val image = BufferedImage(3, 2, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(1, 0, 0x80ff00ff.toInt())
        val acquire = assertNotNull(controller { transfer(DataFlavor.imageFlavor to image) }.prepareClipboardAttachments())
        val upload = acquire().single()
        assertEquals("image/png", upload.mediaType)
        assertEquals(Artifact.Purpose.USER_ATTACHMENT, upload.purpose)
        val decoded = ImageIO.read(ByteArrayInputStream(upload.content))
        assertEquals(image.width, decoded.width)
        assertEquals(image.height, decoded.height)
        assertEquals(image.getRGB(1, 0), decoded.getRGB(1, 0))
    }

    @Test
    fun fileRepresentationWinsOverImageAndDuplicateFileEntriesAreNotRepeated() = runBlocking {
        withFiles { directory ->
            val first = Files.write(directory.resolve("one.bin"), byteArrayOf(0, -1, 42)).toFile()
            val second = Files.writeString(directory.resolve("two.txt"), "second file").toFile()
            val offered = transfer(
                DataFlavor.javaFileListFlavor to listOf(first, first, second),
                DataFlavor.imageFlavor to BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB),
            )
            val uploads = assertNotNull(controller { offered }.prepareClipboardAttachments()).invoke()
            assertEquals(listOf("one.bin", "two.txt"), uploads.map { it.fileName })
            assertContentEquals(byteArrayOf(0, -1, 42), uploads[0].content)
            assertEquals("second file", uploads[1].content.decodeToString())
        }
    }

    @Test
    fun preparationPinsClipboardItemsBeforeAnAsynchronousRead() = runBlocking {
        withFiles { directory ->
            val first = Files.writeString(directory.resolve("first.txt"), "first").toFile()
            val second = Files.writeString(directory.resolve("second.txt"), "second").toFile()
            var clipboard: Transferable = transfer(DataFlavor.javaFileListFlavor to listOf(first))
            val controller = controller { clipboard }
            val prepared = assertNotNull(controller.prepareClipboardAttachments())
            clipboard = transfer(DataFlavor.javaFileListFlavor to listOf(second))
            assertEquals("first", prepared().single().content.decodeToString())
        }
    }

    @Test
    fun uriListAcceptsOnlyExplicitLocalFileItemsAndPreservesEscapedNames() = runBlocking {
        withFiles { directory ->
            val file = Files.writeString(directory.resolve("Файл #1.txt"), "payload").toFile()
            val uri = file.toURI().toASCIIString()
            val offered = transfer(uriFlavor to "# file-manager metadata\r\n$uri\r\n$uri\r\n")
            val uploads = assertNotNull(controller { offered }.prepareClipboardAttachments()).invoke()
            assertEquals(1, uploads.size)
            assertEquals(file.name, uploads.single().fileName)
            assertEquals("payload", uploads.single().content.decodeToString())
        }
    }

    @Test
    fun oversizedFilesAndDirectoriesFailInsteadOfBecomingEmptyAttachments() = runBlocking {
        withFiles { directory ->
            val file = directory.resolve("too-large.bin").toFile()
            RandomAccessFile(file, "rw").use { it.setLength(ArtifactLimits.MAX_FILE_BYTES.toLong() + 1) }
            for (item in listOf(file, directory.toFile())) {
                val prepared = assertNotNull(controller { transfer(DataFlavor.javaFileListFlavor to listOf(item)) }
                    .prepareClipboardAttachments())
                assertFailsWith<LocalizedTextException> { prepared() }
            }
        }
    }

    @Test
    fun unsupportedFlavorDoesNotClaimPasteOrDrop() {
        val source = transfer(DataFlavor("application/x-unknown;class=java.lang.String") to "opaque")
        val controller = controller { source }
        assertTrue(!controller.canReceive(source))
        assertNull(controller.prepareClipboardAttachments())
    }

    private fun controller(clipboard: () -> Transferable?) =
        DesktopAttachmentAcquisitionController({ EnglishTranslation() }, clipboard)

    private fun transfer(vararg values: Pair<DataFlavor, Any>): Transferable = object : Transferable {
        private val contents = values.toMap()
        override fun getTransferDataFlavors(): Array<DataFlavor> = contents.keys.toTypedArray()
        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor in contents
        override fun getTransferData(flavor: DataFlavor): Any = contents[flavor] ?: throw UnsupportedFlavorException(flavor)
    }

    private suspend fun withFiles(action: suspend (Path) -> Unit) {
        val directory = Files.createTempDirectory("composer-attachment-test-")
        try { action(directory) } finally { directory.toFile().deleteRecursively() }
    }

    private val uriFlavor = DataFlavor("text/uri-list;class=java.lang.String")
}
