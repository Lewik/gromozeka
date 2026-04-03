package com.gromozeka.presentation.testsupport.app

import androidx.compose.ui.test.ComposeUiTest
import com.gromozeka.presentation.ui.UiTestTag
import com.gromozeka.presentation.testsupport.compose.captureRoot
import com.gromozeka.presentation.testsupport.compose.captureVisibleNode
import com.gromozeka.presentation.testsupport.compose.clickVisible
import com.gromozeka.presentation.testsupport.compose.inputVisibleText
import com.gromozeka.presentation.testsupport.compose.replaceVisibleText
import com.gromozeka.presentation.testsupport.compose.visibleNode
import java.nio.file.Path

/**
 * High-level actions exposed to app-level UI tests.
 */
class AppTestActions internal constructor(
    private val composeUiTest: ComposeUiTest,
    private val trace: AppTestTrace,
    private val defaultRootScreenshotPath: Path,
) {
    private enum class TraceStep(
        private val prefix: String,
    ) {
        AssertVisible("assert-visible"),
        BeforeClick("before-click"),
        AfterClick("after-click"),
        BeforeInput("before-input"),
        AfterInput("after-input"),
        BeforeReplace("before-replace"),
        AfterReplace("after-replace"),
        CaptureRoot("capture-root"),
        CaptureVisibleNode("capture-visible-node"),
        ;

        fun label(tag: String): String = "$prefix-$tag"
    }

    fun assertVisible(
        tag: UiTestTag,
        timeoutMillis: Long = 5_000,
    ) = assertVisible(tag.value, timeoutMillis)

    fun assertVisible(
        tag: String,
        timeoutMillis: Long = 5_000,
    ) {
        composeUiTest.visibleNode(tag, timeoutMillis)
        trace.capture(composeUiTest, TraceStep.AssertVisible.label(tag))
    }

    fun click(
        tag: UiTestTag,
        timeoutMillis: Long = 5_000,
    ) = click(tag.value, timeoutMillis)

    fun click(
        tag: String,
        timeoutMillis: Long = 5_000,
    ) {
        composeUiTest.visibleNode(tag, timeoutMillis)
        trace.capture(composeUiTest, TraceStep.AssertVisible.label(tag))
        trace.capture(composeUiTest, TraceStep.BeforeClick.label(tag))
        composeUiTest.clickVisible(tag, timeoutMillis)
        trace.capture(composeUiTest, TraceStep.AfterClick.label(tag))
    }

    fun inputText(
        tag: UiTestTag,
        text: String,
        timeoutMillis: Long = 5_000,
    ) = inputText(tag.value, text, timeoutMillis)

    fun inputText(
        tag: String,
        text: String,
        timeoutMillis: Long = 5_000,
    ) {
        composeUiTest.visibleNode(tag, timeoutMillis)
        trace.capture(composeUiTest, TraceStep.AssertVisible.label(tag))
        trace.capture(composeUiTest, TraceStep.BeforeInput.label(tag))
        composeUiTest.inputVisibleText(tag, text, timeoutMillis)
        trace.capture(composeUiTest, TraceStep.AfterInput.label(tag))
    }

    fun replaceText(
        tag: UiTestTag,
        text: String,
        timeoutMillis: Long = 5_000,
    ) = replaceText(tag.value, text, timeoutMillis)

    fun replaceText(
        tag: String,
        text: String,
        timeoutMillis: Long = 5_000,
    ) {
        composeUiTest.visibleNode(tag, timeoutMillis)
        trace.capture(composeUiTest, TraceStep.AssertVisible.label(tag))
        trace.capture(composeUiTest, TraceStep.BeforeReplace.label(tag))
        composeUiTest.replaceVisibleText(tag, text, timeoutMillis)
        trace.capture(composeUiTest, TraceStep.AfterReplace.label(tag))
    }

    fun captureVisibleNode(
        tag: UiTestTag,
        path: Path,
        timeoutMillis: Long = 5_000,
    ) = captureVisibleNode(tag.value, path, timeoutMillis)

    fun captureVisibleNode(
        tag: String,
        path: Path,
        timeoutMillis: Long = 5_000,
    ) {
        composeUiTest.captureVisibleNode(tag, path, timeoutMillis)
        trace.capture(composeUiTest, TraceStep.CaptureVisibleNode.label(tag))
    }

    fun captureRoot(path: Path = defaultRootScreenshotPath) {
        composeUiTest.captureRoot(path)
        trace.capture(composeUiTest, TraceStep.CaptureRoot.label("root"))
    }
}
