package com.gromozeka.presentation.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.gromozeka.domain.model.UserMessageDeliveryMode
import com.gromozeka.domain.service.CurrentUserMessageDeliveryPreferenceService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class UserMessageDeliveryPreferenceSettingTest {
    private class Preference : CurrentUserMessageDeliveryPreferenceService {
        override val mode = MutableStateFlow(UserMessageDeliveryMode.STEER)
        var fail = false
        val saved = mutableListOf<UserMessageDeliveryMode>()
        override suspend fun setMode(mode: UserMessageDeliveryMode) {
            if (fail) error("Synthetic save failure")
            saved += mode; this.mode.value = mode
        }
    }
    @Test fun changingPreferenceUpdatesAllOpenViewsAndAcceptsRemoteChanges() = runDesktopComposeUiTest {
        val service = Preference()
        setContent { GromozekaTheme { Column {
            UserMessageDeliveryPreferenceSetting(service)
            UserMessageDeliveryPreferenceSetting(service)
        } } }
        onAllNodesWithText("Steer — next safe point").assertCountEquals(2)
        onAllNodesWithText("Steer — next safe point")[0].performClick()
        onNodeWithText("After the current turn").performClick()
        waitForIdle()
        assertEquals(listOf(UserMessageDeliveryMode.AFTER_CURRENT_TURN), service.saved)
        onAllNodesWithText("After the current turn").assertCountEquals(2)
        runOnIdle { service.mode.value = UserMessageDeliveryMode.STEER }
        onAllNodesWithText("Steer — next safe point").assertCountEquals(2)
    }
    @Test fun failedSaveIsVisibleAndDoesNotPretendPreferenceChanged() = runDesktopComposeUiTest {
        val service = Preference().apply { fail = true }
        setContent { GromozekaTheme { UserMessageDeliveryPreferenceSetting(service) } }
        onNodeWithText("Steer — next safe point").performClick()
        onNodeWithText("After the current turn").performClick()
        onNodeWithText("Could not save the message delivery preference.").assertExists()
        onNodeWithText("Steer — next safe point").assertExists()
        assertTrue(service.saved.isEmpty())
    }
}
