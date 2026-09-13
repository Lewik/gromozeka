package com.gromozeka.infrastructure.ai.tool

import com.gromozeka.domain.model.Artifact
import com.gromozeka.domain.model.Conversation
import com.gromozeka.domain.service.MAX_TOOL_OUTPUT_DOWNLOAD_CHUNK_BYTES
import com.gromozeka.domain.service.ToolOutputArtifactReader
import com.gromozeka.domain.tool.ToolExecutionContext
import com.gromozeka.domain.tool.requiredWorkspaceRootPath
import com.gromozeka.domain.tool.filesystem.GrzSaveToolOutputTool
import com.gromozeka.domain.tool.filesystem.SaveToolOutputRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

@Service
@ConditionalOnProperty(name = ["gromozeka.runtime.worker.enabled"], havingValue = "true")
class GrzSaveToolOutputToolImpl(private val reader: ToolOutputArtifactReader) : GrzSaveToolOutputTool {
    override fun execute(request: SaveToolOutputRequest, context: ToolExecutionContext?): Map<String, Any> = runBlocking {
        try {
            require(request.mode in setOf("original", "text")) { "mode must be original or text" }
            val conversationId = Conversation.Id(requireNotNull(context?.getString("conversationId")))
            val root = Path.of(context.requiredWorkspaceRootPath()).toRealPath()
            val target = root.resolve(request.path).normalize().toAbsolutePath()
            require(target.startsWith(root) && target != root) { "Destination must be inside the workspace" }
            var ancestor = target.parent
            while (!Files.exists(ancestor)) ancestor = ancestor.parent
            require(ancestor.toRealPath().startsWith(root)) { "Destination resolves outside the workspace" }
            Files.createDirectories(target.parent)
            require(target.parent.toRealPath().startsWith(root)) { "Destination resolves outside the workspace" }
            require(request.overwrite || !Files.exists(target)) { "Destination exists; set overwrite=true to replace it" }
            require(!Files.isDirectory(target)) { "Destination is a directory" }
            val temporary = Files.createTempFile(target.parent, ".tool-output-", ".tmp")
            var converted: Path? = null
            try {
                val artifactId = Artifact.Id(request.artifact_id)
                val digest = MessageDigest.getInstance("SHA-256")
                var offset = 0L
                var totalBytes: Long? = null
                var expectedHash: String? = null
                Files.newOutputStream(temporary).use { output ->
                    do {
                        context?.cancellationSignal?.throwIfCancellationRequested()
                        val chunk = reader.read(conversationId, artifactId, offset, MAX_TOOL_OUTPUT_DOWNLOAD_CHUNK_BYTES)
                        require(chunk.artifact.id == artifactId && chunk.offset == offset) { "Artifact response identity mismatch" }
                        require(totalBytes == null || totalBytes == chunk.totalBytes) { "Artifact size changed during export" }
                        require(offset == 0L || expectedHash == chunk.sha256) { "Artifact hash changed during export" }
                        totalBytes = chunk.totalBytes
                        expectedHash = chunk.sha256
                        val bytes = chunk.content.bytes()
                        require(bytes.size <= MAX_TOOL_OUTPUT_DOWNLOAD_CHUNK_BYTES && offset + bytes.size <= chunk.totalBytes)
                        require(bytes.isNotEmpty() || offset == chunk.totalBytes) { "Artifact download stopped before its end" }
                        output.write(bytes)
                        digest.update(bytes)
                        offset += bytes.size
                    } while (offset < requireNotNull(totalBytes))
                }
                val sourceHash = digest.digest().hex()
                require(expectedHash == null || sourceHash == expectedHash) { "Artifact checksum mismatch" }
                val ready = if (request.mode == "text") {
                    val decoder = Charset.forName(request.source_encoding).newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                    val textFile = Files.createTempFile(target.parent, ".tool-output-text-", ".tmp")
                    converted = textFile
                    InputStreamReader(Files.newInputStream(temporary), decoder).use { input ->
                        Files.newBufferedWriter(textFile, Charsets.UTF_8).use { output -> input.copyTo(output) }
                    }
                    textFile
                } else temporary
                val savedBytes = Files.size(ready)
                val savedDigest = MessageDigest.getInstance("SHA-256")
                Files.newInputStream(ready).use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        savedDigest.update(buffer, 0, count)
                    }
                }
                context?.cancellationSignal?.throwIfCancellationRequested()
                if (request.overwrite) {
                    try {
                        Files.move(ready, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                        Files.move(ready, target, StandardCopyOption.REPLACE_EXISTING)
                    }
                } else Files.move(ready, target)
                mapOf("success" to true, "path" to target.toString(), "bytes" to savedBytes,
                    "sha256" to savedDigest.digest().hex(), "source_sha256" to sourceHash,
                    "artifact_id" to artifactId.value, "mode" to request.mode)
            } finally {
                Files.deleteIfExists(temporary)
                converted?.let(Files::deleteIfExists)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            mapOf("success" to false, "error" to (error.message ?: "Artifact export failed"))
        }
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
}
