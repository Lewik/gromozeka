package com.gromozeka.updates

import com.gromozeka.domain.model.ApplicationUpdateReport
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException

@Serializable
data class LocalUpdateState(val installationId: String = "client-${UUID.randomUUID()}", val report: ApplicationUpdateReport? = null)

class UpdateLocalStore(val directory: File) {
    private val lock = locks.computeIfAbsent(directory.canonicalPath) { Any() }
    private val stateFile = File(directory, "state.json")
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    init { directory.mkdirs(); require(directory.isDirectory) }

    fun read(): LocalUpdateState = synchronized(lock) {
        val state = if (stateFile.exists()) json.decodeFromString<LocalUpdateState>(stateFile.readText())
            else LocalUpdateState().also(::write)
        val result = File(directory, "result.json")
        if (!result.exists()) return state
        val report = json.decodeFromString<ApplicationUpdateReport>(result.readText().removePrefix("\uFEFF"))
        val updated = if (state.report?.requestId == report.requestId && !state.report.phase.terminal) state.copy(report = report) else state
        write(updated)
        result.delete()
        return updated
    }

    fun write(state: LocalUpdateState): Unit = synchronized(lock) {
        val temporary = File(directory, "state.json.new")
        temporary.outputStream().use { stream -> stream.write(json.encodeToString(state).encodeToByteArray()); stream.fd.sync() }
        moveUpdateFile(temporary, stateFile)
    }

    fun report(value: ApplicationUpdateReport?): Unit = synchronized(lock) { write(read().copy(report = value)) }

    fun acceptPoll(expected: LocalUpdateState, id: String, report: ApplicationUpdateReport?): Unit = synchronized(lock) {
        val latest = read()
        write(latest.copy(installationId = id, report = if (latest.report == expected.report) report else latest.report))
    }

    internal suspend fun <T> withAgentLock(block: suspend () -> T): T =
        RandomAccessFile(File(directory, "agent.lock"), "rw").use { file ->
            val lease = try { file.channel.tryLock() } catch (_: OverlappingFileLockException) { null }
            checkNotNull(lease) { "Another application instance is handling this update" }.use { block() }
        }

    companion object {
        private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()
        fun key(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
