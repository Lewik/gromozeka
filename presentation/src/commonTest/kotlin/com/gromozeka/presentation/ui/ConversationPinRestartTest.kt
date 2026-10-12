package com.gromozeka.presentation.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/** Tests the exact suspend boundary used by the parent Restart callback, not a full app/device launch. */
class ConversationPinRestartTest {
    @Test
    fun successfulRestartRunsOnceWithoutFailureNotification() = runTest {
        var attempts = 0
        var failures = 0
        performPinPreservingRestart(restart = { attempts++ }, reportFailure = { failures++ })
        assertEquals(1, attempts)
        assertEquals(0, failures)
    }

    @Test
    fun failureProducesOnlyTheFixedNotificationWithoutRetry() = runTest {
        var attempts = 0
        var failures = 0
        performPinPreservingRestart(
            restart = {
                attempts++
                throw IllegalStateException("PRIVATE_PIN_BODY", IllegalArgumentException("PRIVATE_STORAGE_DETAIL"))
            },
            // The callback deliberately has no Throwable/text argument to display or log.
            reportFailure = { failures++ },
        )
        assertEquals(1, attempts)
        assertEquals(1, failures)
    }

    @Test
    fun cancellationIsPreservedAndIsNotShownAsRestartFailure() = runTest {
        val cancelled = CancellationException("Synthetic cancellation")
        var attempts = 0
        var failures = 0
        val actual = assertFailsWith<CancellationException> {
            performPinPreservingRestart(
                restart = { attempts++; throw cancelled },
                reportFailure = { failures++ },
            )
        }
        assertSame(cancelled, actual)
        assertEquals(1, attempts)
        assertEquals(0, failures)
    }
}
