package com.gromozeka.presentation

import com.gromozeka.presentation.testsupport.app.withAppTestContext
import com.gromozeka.presentation.ui.UiTestTag
import kotlin.test.Test

class AppStartupSmokeTest {

    @Test
    fun appShellLoads() = withAppTestContext(
        owner = this,
        testName = "appShellLoads",
    ) {
            assertVisible(UiTestTag.AppRoot)
            assertVisible(UiTestTag.TabRow)
            assertVisible(UiTestTag.ProjectsTab)
            assertVisible(UiTestTag.AgentsTab)

            captureRoot()
    }
}
