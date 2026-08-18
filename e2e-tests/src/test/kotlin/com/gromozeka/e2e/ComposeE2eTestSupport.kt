package com.gromozeka.e2e

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.gromozeka.presentation.ui.ClientPlatform
import com.gromozeka.presentation.ui.GromozekaApp
import com.gromozeka.presentation.ui.UiTestTag
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

@OptIn(ExperimentalTestApi::class)
internal fun runGromozekaUiTest(
    scenarioName: String,
    forceCompactLayout: Boolean = false,
    clientPlatform: ClientPlatform = ClientPlatform.DESKTOP,
    viewportWidth: Int = 1280,
    viewportHeight: Int = 800,
    block: ComposeUiTest.(E2eClient) -> Unit,
) {
    E2eEnvironment.openClient().use { client ->
        runDesktopComposeUiTest(width = viewportWidth, height = viewportHeight) {
            setContent {
                GromozekaApp(
                    appComponents = client.components,
                    skipLoadingScreen = true,
                    showRuntimePanelInitially = false,
                    forceCompactLayout = forceCompactLayout,
                    clientPlatform = clientPlatform,
                )
            }
            try {
                waitForTag(UiTestTag.AppRoot)
                block(client)
            } catch (error: Throwable) {
                writeSemanticsSnapshot(scenarioName, onRoot(useUnmergedTree = true).printToString())
                throw error
            }
        }
    }
}

internal fun ComposeUiTest.waitForTag(tag: UiTestTag, timeoutMillis: Long = 30_000) {
    waitUntil(timeoutMillis = timeoutMillis) {
        onAllNodesWithTag(tag.value, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }
    onNodeWithTag(tag.value, useUnmergedTree = true).assertExists()
}

private fun writeSemanticsSnapshot(scenarioName: String, content: String) {
    val directory = Path.of(
        System.getProperty("gromozeka.e2e.artifactsDir")
            ?: error("gromozeka.e2e.artifactsDir is not configured")
    ).resolve("failures")
    Files.createDirectories(directory)
    Files.writeString(
        directory.resolve("$scenarioName-${ProcessHandle.current().pid()}.semantics.txt"),
        content,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
    )
}
