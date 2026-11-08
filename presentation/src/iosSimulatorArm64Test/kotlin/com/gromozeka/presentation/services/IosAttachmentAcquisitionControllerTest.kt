@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.gromozeka.presentation.services

import platform.Foundation.NSData
import platform.Foundation.NSItemProvider
import platform.Foundation.NSURL
import platform.UniformTypeIdentifiers.UTTypeFileURL
import platform.UniformTypeIdentifiers.UTType
import platform.UniformTypeIdentifiers.UTTypeImage
import platform.UniformTypeIdentifiers.conformsToType
import kotlin.test.Test
import kotlin.test.BeforeTest
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IosAttachmentAcquisitionControllerTest {
    private val controller = IosAttachmentAcquisitionController()

    @BeforeTest
    fun requireSystemTypeDatabase() {
        assertTrue(UTType.typeWithIdentifier("public.png")?.conformsToType(UTTypeImage) == true,
            "Native platform tests require a booted simulator; use python3 scripts/run-ios-client-tests.py")
    }

    @Test
    fun plainTextWithASuggestedFilenameStillRemainsText() {
        val provider = NSItemProvider(item = NSData(), typeIdentifier = "public.utf8-plain-text")
        provider.suggestedName = "copied text.txt"
        assertFalse(controller.canReceiveProvider(provider))
    }

    @Test
    fun webUrlsAreNotTreatedAsLocalFiles() {
        val provider = NSItemProvider(item = NSURL.URLWithString("https://example.com/document.pdf"), typeIdentifier = "public.url")
        provider.suggestedName = "document.pdf"
        assertFalse(controller.canReceiveProvider(provider))
    }

    @Test
    fun imagesAndBinaryDocumentTypesCanBeReceivedWithoutInventingAFileName() {
        for (type in listOf("public.png", "public.jpeg", "com.adobe.pdf")) {
            val provider = NSItemProvider(item = NSData(), typeIdentifier = type)
            assertTrue(controller.canReceiveProvider(provider), "$type: registered=${provider.registeredTypeIdentifiers}")
        }
    }

    @Test
    fun explicitLocalFileUrlCanRepresentATextFile() {
        val provider = NSItemProvider(item = NSURL.fileURLWithPath("/synthetic/not-opened.txt"),
            typeIdentifier = UTTypeFileURL.identifier)
        assertTrue(controller.canReceiveProvider(provider))
    }
}
