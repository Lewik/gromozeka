package com.gromozeka.infrastructure.ai.claude

import com.gromozeka.domain.model.ai.ClaudeCodeSessionState
import com.gromozeka.domain.repository.ClaudeCodeSessionStateRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.springframework.stereotype.Service
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

@Service
internal class FileClaudeCodeSessionStateRepository : ClaudeCodeSessionStateRepository {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    override suspend fun find(key: ClaudeCodeSessionState.Key): ClaudeCodeSessionState? = withContext(Dispatchers.IO) {
        val file = sessionFile(key)
        if (!file.isFile) return@withContext null
        json.decodeFromString<ClaudeCodeSessionState>(file.readText())
    }

    override suspend fun save(state: ClaudeCodeSessionState): ClaudeCodeSessionState = withContext(Dispatchers.IO) {
        val file = sessionFile(state.key)
        file.parentFile.mkdirs()
        val tempFile = File(file.parentFile, "${file.name}.tmp")
        tempFile.writeText(json.encodeToString(state))
        if (!tempFile.renameTo(file)) {
            tempFile.copyTo(file, overwrite = true)
            tempFile.delete()
        }
        state
    }

    override suspend fun delete(key: ClaudeCodeSessionState.Key): Unit = withContext(Dispatchers.IO) {
        sessionFile(key).delete()
    }

    private fun sessionFile(key: ClaudeCodeSessionState.Key): File =
        File(sessionDirectory(), "${key.fingerprint()}.json")

    private fun sessionDirectory(): File {
        val home = System.getProperty("GROMOZEKA_HOME")
            ?: System.getenv("GROMOZEKA_HOME")
            ?: error("GROMOZEKA_HOME is required for Claude Code session persistence")
        return File(home, "runtime/claude-code/sessions")
    }

    private fun ClaudeCodeSessionState.Key.fingerprint(): String =
        sha256(
            listOf(
                conversationId.value,
                threadId.value,
                projectId.value,
                projectPathFingerprint,
                connectionId.value,
                modelConfigurationId.value,
                modelName,
            ).joinToString("\u001F")
        )

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
