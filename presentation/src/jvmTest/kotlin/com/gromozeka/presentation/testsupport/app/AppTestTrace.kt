package com.gromozeka.presentation.testsupport.app

import androidx.compose.ui.test.ComposeUiTest
import com.gromozeka.presentation.testsupport.compose.captureRoot
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Collects step-by-step screenshots for a single app test run.
 */
class AppTestTrace(
    val directory: Path,
) {

    private var stepCounter: Int = 0

    fun capture(
        composeUiTest: ComposeUiTest,
        label: String,
    ) {
        val stepNumber = ++stepCounter
        val fileName = "${stepNumber.toString().padStart(3, '0')}-${label.sanitizePathSegment()}.png"
        val path = directory.resolve(fileName)
        composeUiTest.captureRoot(path)
        appendStepLine("${stepNumber.toString().padStart(3, '0')} $label")
    }

    fun cleanup() {
        runCatching { directory.toFile().deleteRecursively() }
    }

    private fun appendStepLine(line: String) {
        Files.writeString(
            directory.resolve("steps.txt"),
            "$line\n",
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
    }
}
