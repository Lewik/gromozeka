package com.gromozeka.presentation.testsupport.compose

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import com.gromozeka.presentation.ui.UiTestTag
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

fun ComposeUiTest.visibleNode(
    tag: UiTestTag,
    timeoutMillis: Long = 5_000,
): SemanticsNodeInteraction = visibleNode(tag.value, timeoutMillis)

fun ComposeUiTest.visibleNode(
    tag: String,
    timeoutMillis: Long = 5_000,
): SemanticsNodeInteraction {
    waitUntilAtLeastOneExists(hasTestTag(tag), timeoutMillis)
    return onNodeWithTag(tag).assertIsDisplayed()
}

fun ComposeUiTest.clickVisible(
    tag: UiTestTag,
    timeoutMillis: Long = 5_000,
) = clickVisible(tag.value, timeoutMillis)

fun ComposeUiTest.clickVisible(
    tag: String,
    timeoutMillis: Long = 5_000,
) {
    visibleNode(tag, timeoutMillis).performClick()
}

fun ComposeUiTest.inputVisibleText(
    tag: UiTestTag,
    text: String,
    timeoutMillis: Long = 5_000,
) = inputVisibleText(tag.value, text, timeoutMillis)

fun ComposeUiTest.inputVisibleText(
    tag: String,
    text: String,
    timeoutMillis: Long = 5_000,
) {
    visibleNode(tag, timeoutMillis).performTextInput(text)
}

fun ComposeUiTest.replaceVisibleText(
    tag: UiTestTag,
    text: String,
    timeoutMillis: Long = 5_000,
) = replaceVisibleText(tag.value, text, timeoutMillis)

fun ComposeUiTest.replaceVisibleText(
    tag: String,
    text: String,
    timeoutMillis: Long = 5_000,
) {
    visibleNode(tag, timeoutMillis).performTextReplacement(text)
}

fun ComposeUiTest.captureVisibleNode(
    tag: UiTestTag,
    path: Path,
    timeoutMillis: Long = 5_000,
) = captureVisibleNode(tag.value, path, timeoutMillis)

fun ComposeUiTest.captureVisibleNode(
    tag: String,
    path: Path,
    timeoutMillis: Long = 5_000,
) {
    path.parent?.let(Files::createDirectories)
    visibleNode(tag, timeoutMillis)
        .captureToImage()
        .savePng(path)
}

fun ComposeUiTest.captureRoot(path: Path) {
    path.parent?.let(Files::createDirectories)
    onRoot().captureToImage().savePng(path)
}

fun ImageBitmap.savePng(path: Path) {
    path.parent?.let(Files::createDirectories)

    val pixels = toPixelMap()
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    for (y in 0 until height) {
        for (x in 0 until width) {
            image.setRGB(x, y, pixels.buffer[pixels.bufferOffset + y * pixels.stride + x])
        }
    }
    ImageIO.write(image, "png", path.toFile())
}
