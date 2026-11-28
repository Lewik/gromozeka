package com.gromozeka.worker

import com.gromozeka.domain.model.ai.AiAdmissionCompletion
import com.gromozeka.domain.model.ai.AiAdmissionLease
import com.gromozeka.domain.model.ai.AiAdmissionOperation
import com.gromozeka.domain.model.ai.AiAdmissionOutcome
import com.gromozeka.domain.model.ai.AiAdmissionTarget
import com.gromozeka.domain.model.ai.AiConcurrencyGroup
import com.gromozeka.domain.model.ai.AiConnection
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import com.gromozeka.domain.service.ConversationRuntimeWorkerSessionId
import com.gromozeka.domain.service.SettingsProvider
import com.gromozeka.remote.protocol.WorkerGatewayMessage
import com.gromozeka.worker.runtime.WorkerRequestReceipt
import com.gromozeka.shared.utils.sha256
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.mockito.Mockito
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

class JvmWorkerRequestJournalTest {
    @Test
    fun `new journal generation does not read previous RPC receipts`() = runBlocking {
        val directory = Files.createTempDirectory("worker-journal-generation-test-")
        val settings = Mockito.mock(SettingsProvider::class.java)
        Mockito.`when`(settings.homeDirectory).thenReturn(directory.toString())
        val identity = ConversationRuntimeWorkerIdentity(ConversationRuntimeWorkerId("test"), ConversationRuntimeWorkerSessionId("new"))
        val oldDirectory = directory.resolve("worker-requests").resolve(identity.workerId.value.sha256())
        Files.createDirectories(oldDirectory)
        Files.write(oldDirectory.resolve("discarded.receipt"), byteArrayOf(1))
        try {
            val journal = JvmWorkerRequestJournal(settings, identity)
            try { assertEquals(emptyList(), journal.load()) }
            finally { journal.close() }
        } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

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
            deleteTree(directory)
        }
    }

    @Test
    fun `protocol 36 finite admission records migrate and remain recoverable`() = runBlocking {
        val directory = Files.createTempDirectory("worker-admission-journal-migration-")
        val settings = Mockito.mock(SettingsProvider::class.java)
        Mockito.`when`(settings.homeDirectory).thenReturn(directory.toString())
        val identity = ConversationRuntimeWorkerIdentity(
            ConversationRuntimeWorkerId("test"),
            ConversationRuntimeWorkerSessionId("first"),
        )
        val lease = admissionLease("legacy-finite", identity)
        val completion = AiAdmissionCompletion(lease, AiAdmissionOutcome.OWNER_STOPPED)

        try {
            val journal = JvmWorkerRequestJournal(settings, identity)
            try {
                journal.saveAiAdmission(lease)
                journal.saveAiAdmissionCompletion(completion)
                val journalDirectory = journalDirectory(directory, identity)
                val keyPath = journalDirectory.resolve("journal.key")
                val admissionPath = journalDirectory.resolve("${lease.operation.id.sha256()}.admission")
                val completionPath = journalDirectory.resolve("${lease.operation.id.sha256()}.admission-completion")
                rewriteAsProtocol36(admissionPath, keyPath, JsonNull)
                rewriteAsProtocol36(completionPath, keyPath, JsonNull)

                assertEquals(listOf(lease), journal.loadAiAdmissions())
                assertEquals(listOf(completion), journal.loadAiAdmissionCompletions())

                val migratedLease = readEncryptedJson(admissionPath, keyPath)
                assertFalse("accepted" in migratedLease)
                assertFalse("backgroundSessionId" in migratedLease.getValue("operation").jsonObject)
                val migratedCompletionLease = readEncryptedJson(completionPath, keyPath).getValue("lease").jsonObject
                assertFalse("accepted" in migratedCompletionLease)
                assertFalse("backgroundSessionId" in migratedCompletionLease.getValue("operation").jsonObject)
            } finally {
                journal.close()
            }
        } finally {
            deleteTree(directory)
        }
    }

    @Test
    fun `protocol 36 detached voice admission records are discarded`() = runBlocking {
        val directory = Files.createTempDirectory("worker-detached-admission-journal-")
        val settings = Mockito.mock(SettingsProvider::class.java)
        Mockito.`when`(settings.homeDirectory).thenReturn(directory.toString())
        val identity = ConversationRuntimeWorkerIdentity(
            ConversationRuntimeWorkerId("test"),
            ConversationRuntimeWorkerSessionId("first"),
        )
        val lease = admissionLease("legacy-detached", identity)
        val completion = AiAdmissionCompletion(lease, AiAdmissionOutcome.SUCCEEDED)

        try {
            val journal = JvmWorkerRequestJournal(settings, identity)
            try {
                journal.saveAiAdmission(lease)
                journal.saveAiAdmissionCompletion(completion)
                val journalDirectory = journalDirectory(directory, identity)
                val keyPath = journalDirectory.resolve("journal.key")
                val admissionPath = journalDirectory.resolve("${lease.operation.id.sha256()}.admission")
                val completionPath = journalDirectory.resolve("${lease.operation.id.sha256()}.admission-completion")
                val backgroundSessionId = JsonPrimitive("capture-session")
                rewriteAsProtocol36(admissionPath, keyPath, backgroundSessionId)
                rewriteAsProtocol36(completionPath, keyPath, backgroundSessionId)

                assertEquals(emptyList(), journal.loadAiAdmissionCompletions())
                assertEquals(emptyList(), journal.loadAiAdmissions())
                assertFalse(Files.exists(admissionPath))
                assertFalse(Files.exists(completionPath))
            } finally {
                journal.close()
            }
        } finally {
            deleteTree(directory)
        }
    }

    private fun admissionLease(
        id: String,
        identity: ConversationRuntimeWorkerIdentity,
    ) = AiAdmissionLease(
        operation = AiAdmissionOperation(
            id = id,
            target = AiAdmissionTarget.Connection(AiConnection.Id("connection")),
            purpose = "TEST",
        ),
        groupId = AiConcurrencyGroup.DEFAULT_ID,
        acquiredAt = Clock.System.now(),
        workerId = identity.workerId.value,
        workerRequestId = "request",
    )

    private fun journalDirectory(
        root: Path,
        identity: ConversationRuntimeWorkerIdentity,
    ): Path = root.resolve("worker-requests-v2").resolve(identity.workerId.value.sha256())

    private fun rewriteAsProtocol36(
        path: Path,
        keyPath: Path,
        backgroundSessionId: JsonElement,
    ) {
        val current = readEncryptedJson(path, keyPath)
        val legacy = if ("lease" in current) {
            JsonObject(current + ("lease" to legacyLease(current.getValue("lease").jsonObject, backgroundSessionId)))
        } else {
            legacyLease(current, backgroundSessionId)
        }
        writeEncryptedJson(path, keyPath, legacy)
    }

    private fun legacyLease(
        current: JsonObject,
        backgroundSessionId: JsonElement,
    ): JsonObject {
        val operation = current.getValue("operation").jsonObject
        return JsonObject(
            current +
                ("accepted" to JsonPrimitive(false)) +
                ("operation" to JsonObject(operation + ("backgroundSessionId" to backgroundSessionId)))
        )
    }

    private fun readEncryptedJson(path: Path, keyPath: Path): JsonObject {
        val stored = Files.readAllBytes(path)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(Files.readAllBytes(keyPath), "AES"),
            GCMParameterSpec(128, stored.copyOfRange(1, 13)),
        )
        cipher.updateAAD(path.fileName.toString().encodeToByteArray())
        val plainText = cipher.doFinal(stored.copyOfRange(13, stored.size)).decodeToString()
        return Json.parseToJsonElement(plainText).jsonObject
    }

    private fun writeEncryptedJson(path: Path, keyPath: Path, value: JsonObject) {
        val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(Files.readAllBytes(keyPath), "AES"),
            GCMParameterSpec(128, nonce),
        )
        cipher.updateAAD(path.fileName.toString().encodeToByteArray())
        Files.write(
            path,
            byteArrayOf(1) + nonce + cipher.doFinal(value.toString().encodeToByteArray()),
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING,
        )
    }

    private fun deleteTree(directory: Path) {
        Files.walk(directory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
        }
    }
}
