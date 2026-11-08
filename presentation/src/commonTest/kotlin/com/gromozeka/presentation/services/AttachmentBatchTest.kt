package com.gromozeka.presentation.services

import com.gromozeka.domain.model.Artifact
import com.gromozeka.domain.model.ArtifactLimits
import com.gromozeka.domain.model.ArtifactUpload
import com.gromozeka.presentation.services.translation.LocalizedTextException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AttachmentBatchTest {
    @Test
    fun preservesOrderAndHandlesAnEmptyOfferWithoutReading() = runTest {
        assertTrue(readAttachmentBatch(emptyList<Int>()) { error("must not read") }.isEmpty())
        val uploads = readAttachmentBatch(listOf("second", "first")) { upload(it, byteArrayOf(1)) }
        assertEquals(listOf("second", "first"), uploads.map { it.fileName })
    }

    @Test
    fun excessiveCountIsRejectedBeforeStartingAnyRead() = runTest {
        var reads = 0
        assertFailsWith<LocalizedTextException> {
            readAttachmentBatch((0..ArtifactLimits.MAX_ARTIFACTS_PER_MESSAGE).toList()) {
                reads++; upload(it.toString(), byteArrayOf(1))
            }
        }
        assertEquals(0, reads)
    }

    @Test
    fun totalLimitAcceptsTheBoundaryAndStopsBeforeReadingTheRest() = runTest {
        val content = ByteArray(ArtifactLimits.MAX_FILE_BYTES)
        val accepted = readAttachmentBatch(listOf(1, 2)) { upload(it.toString(), content) }
        assertEquals(ArtifactLimits.MAX_TOTAL_BYTES_PER_MESSAGE, accepted.sumOf { it.content.size.toLong() })
        var reads = 0
        assertFailsWith<LocalizedTextException> {
            readAttachmentBatch(listOf(1, 2, 3, 4)) { reads++; upload(it.toString(), content) }
        }
        assertEquals(3, reads)
    }

    @Test
    fun oversizedIndividualContentIsRejected() = runTest {
        assertFailsWith<LocalizedTextException> {
            readAttachmentBatch(listOf(1)) { upload("oversized", ByteArray(ArtifactLimits.MAX_FILE_BYTES + 1)) }
        }
    }

    @Test
    fun failuresAndCancellationAreNotTurnedIntoSuccessfulPartialLists() = runTest {
        val failure = IllegalStateException("synthetic read failure")
        assertSame(failure, assertFailsWith<IllegalStateException> {
            readAttachmentBatch(listOf(1, 2)) { if (it == 2) throw failure; upload("first", byteArrayOf(1)) }
        })
        val cancelled = CancellationException("synthetic cancellation")
        assertSame(cancelled, assertFailsWith<CancellationException> {
            readAttachmentBatch(listOf(1)) { throw cancelled }
        })
    }

    private fun upload(name: String, content: ByteArray) =
        ArtifactUpload(name, "application/octet-stream", content, Artifact.Purpose.USER_ATTACHMENT)
}
