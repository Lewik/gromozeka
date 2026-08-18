package com.gromozeka.e2e

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.gromozeka.presentation.ui.UiTestTag
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.Test

class StateSyncE2eTest {
    @Test
    fun displaysProjectCreatedByAnotherConnectedClient() = runGromozekaUiTest("state-sync") { _ ->
        waitForTag(UiTestTag.ManageProjectsButton)
        onNodeWithTag(UiTestTag.ManageProjectsButton.value).performClick()
        waitForTag(UiTestTag.ProjectManager)

        val project = E2eEnvironment.openClient().use { writer ->
            runBlocking {
                writer.components.projectService.create(
                    name = "Synced project ${UUID.randomUUID().toString().take(8)}",
                    description = "Created from a second authenticated client",
                )
            }
        }

        waitForTag(UiTestTag.ProjectItem(project.id.value))
        onNodeWithTag(UiTestTag.ProjectItem(project.id.value).value).assertTextContains(project.name)
    }
}
