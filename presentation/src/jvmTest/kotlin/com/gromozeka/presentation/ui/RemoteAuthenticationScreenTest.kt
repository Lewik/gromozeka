package com.gromozeka.presentation.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import com.gromozeka.remote.protocol.DeviceConnectionChallenge
import com.gromozeka.remote.protocol.DeviceConnectionConsumeResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Instant

class RemoteAuthenticationScreenTest {
    @Test fun passwordSubmissionDoesNotDependOnAnExpiredDeviceChallenge() = runComposeUiTest {
        var started = 0
        var consumed = 0
        var submitted: RemoteAuthenticationInput? = null
        showAuthentication(
            start = { expiredChallenge(++started) },
            consume = { consumed++; error("An expired challenge must not be consumed") },
            submit = { submitted = it },
        )
        onNodeWithText("Connection code expired").assertExists()
        onNodeWithText("Use username and password instead").performScrollTo().performClick()
        onAllNodes(hasSetTextAction())[0].performTextInput("test-user")
        onAllNodes(hasSetTextAction())[1].performTextInput("test-password-123")
        onNodeWithText("Sign in", substring = false).performScrollTo().assertIsEnabled().performClick()
        runOnIdle {
            assertEquals("test-user", assertNotNull(submitted).username)
            assertEquals("test-password-123", assertNotNull(submitted).password)
            assertEquals(1, started)
            assertEquals(0, consumed)
        }
        onNodeWithText("Use connection code").performScrollTo().assertIsEnabled().performClick()
        waitUntil { started == 2 }
        onNodeWithText("TEST-0002").assertExists()
    }

    @Test fun preferredPasswordDoesNotCreateADeviceChallengeButCanSwitchToQr() = runComposeUiTest {
        var started = 0
        showAuthentication(preferPassword = true, start = { expiredChallenge(++started) })
        onNodeWithText("Sign in", substring = false).assertExists()
        runOnIdle { assertEquals(0, started) }
        onNodeWithText("Use connection code").performScrollTo().assertIsEnabled().performClick()
        waitUntil { started == 1 }
        onNodeWithText("TEST-0001").assertExists()
    }

    @Test fun failedQrCreationDoesNotPreventPasswordLoginOrRetryingQr() = runComposeUiTest {
        var started = 0
        var submissions = 0
        showAuthentication(
            start = { if (++started == 1) error("Device code unavailable") else expiredChallenge(started) },
            submit = { submissions++ },
        )
        onNodeWithText("Use username and password instead").performScrollTo().performClick()
        onAllNodes(hasSetTextAction())[0].performTextInput("test-user")
        onAllNodes(hasSetTextAction())[1].performTextInput("test-password-123")
        onNodeWithText("Sign in", substring = false).performScrollTo().performClick()
        runOnIdle { assertEquals(1, submissions) }
        onNodeWithText("Use connection code").performScrollTo().assertIsEnabled().performClick()
        waitUntil { started == 2 }
        onNodeWithText("TEST-0002").assertExists()
    }

    private fun ComposeUiTest.showAuthentication(
        preferPassword: Boolean = false,
        start: suspend () -> DeviceConnectionChallenge,
        consume: suspend (String) -> DeviceConnectionConsumeResponse = { error("Must not consume an expired challenge") },
        submit: (RemoteAuthenticationInput) -> Unit = {},
    ) {
        setContent {
            MaterialTheme {
                RemoteAuthenticationScreen(
                    initialized = true,
                    submitting = false,
                    error = null,
                    onSubmit = submit,
                    onStartDeviceConnection = start,
                    onConsumeDeviceConnection = consume,
                    deviceConnectionVerificationUrl = { "https://example.com/?connectDevice=${it.userCode}" },
                    onDeviceConnected = { error("Password login is not device-code consumption") },
                    preferPassword = preferPassword,
                )
            }
        }
    }

    private fun expiredChallenge(number: Int) = DeviceConnectionChallenge(
        deviceToken = "expired-device-token-$number",
        userCode = "TEST-${number.toString().padStart(4, '0')}",
        verificationPath = "/",
        verificationPathComplete = "/?connectDevice=TEST-$number",
        expiresAt = Instant.fromEpochMilliseconds(0),
        pollIntervalSeconds = 5,
    )
}
