package com.gromozeka.worker

import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import com.gromozeka.domain.service.ConversationRuntimeWorkerSessionId
import com.gromozeka.domain.service.SettingsProvider
import com.gromozeka.remote.protocol.WorkerGatewayMessage
import com.gromozeka.worker.runtime.WorkerRequestReceipt
import kotlinx.coroutines.runBlocking
import org.mockito.Mockito
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

class JvmWorkerRequestJournalTest {
    @Test
    fun `receipt is encrypted atomic recoverable and exclusively owned`() = runBlocking {
        val directory = Files.createTempDirectory("worker-journal-test-")
        val settings = Mockito.mock(SettingsProvider::class.java)
        Mockito.`when`(settings.homeDirectory).thenReturn(directory.toString())
        val identity = ConversationRuntimeWorkerIdentity(ConversationRuntimeWorkerId("test"), ConversationRuntimeWorkerSessionId("first"))
        val receipt = WorkerRequestReceipt(
            "request", "hash", Clock.System.now() + 30.seconds, WorkerRequestReceipt.State.COMPLETED,
            WorkerGatewayMessage.Response("request", WorkerGatewayMessage.Response.Status.SUCCEEDED, "private-result-secret".encodeToByteArray()),
        )
        try {
            val journal = JvmWorkerRequestJournal(settings, identity)
            try {
                journal.save(receipt)
                assertEquals(listOf(receipt), journal.load())
                assertFails { JvmWorkerRequestJournal(settings, identity) }
                Files.walk(directory).use { paths ->
                    paths.filter { it.fileName.toString().endsWith(".receipt") }.forEach {
                        assertFalse(Files.readAllBytes(it).decodeToString().contains("private-result-secret"))
                    }
                }
            } finally { journal.close() }
            val restarted = JvmWorkerRequestJournal(settings, identity.copy(sessionId = ConversationRuntimeWorkerSessionId("second")))
            try {
                assertEquals(listOf(receipt), restarted.load())
                restarted.save(receipt.copy(state = WorkerRequestReceipt.State.ACKNOWLEDGED, response = null))
                assertEquals(null, restarted.load().single().response)
                restarted.delete(receipt.id)
                assertEquals(emptyList(), restarted.load())
            } finally { restarted.close() }
        } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
}
