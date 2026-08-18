package com.gromozeka.e2e

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.gromozeka.presentation.ui.ClientPlatform
import com.gromozeka.presentation.ui.UiTestTag
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.Test

class CompactLayoutE2eTest {
    @Test
    fun opensConversationInMobileLayout() = runGromozekaUiTest(
        scenarioName = "compact-layout",
        forceCompactLayout = true,
        clientPlatform = ClientPlatform.WEB_TOUCH,
        viewportWidth = 390,
        viewportHeight = 844,
    ) { client ->
        val project = runBlocking {
            client.components.projectService.create(
                name = "Compact project ${UUID.randomUUID().toString().take(8)}",
                description = "Project for compact JVM layout verification",
            )
        }

        waitForTag(UiTestTag.NewSessionButton(project.id.value))
        onNodeWithTag(UiTestTag.NewSessionButton(project.id.value).value).performClick()
        waitForTag(UiTestTag.SessionScreen)
        waitForTag(UiTestTag.MessageInput)
    }
}
