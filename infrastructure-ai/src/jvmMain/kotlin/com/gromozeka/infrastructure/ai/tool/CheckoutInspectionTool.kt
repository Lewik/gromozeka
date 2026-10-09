package com.gromozeka.infrastructure.ai.tool

import com.gromozeka.domain.slot.SlotCheckoutObservation
import com.gromozeka.domain.tool.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.time.Clock

/** Fixed Git read operations without a shell/network; disable fsmonitor and optional index writes. */
class CheckoutInspector {
    suspend fun inspect(root: String): SlotCheckoutObservation = withContext(Dispatchers.IO) {
        require(File(root).isDirectory) { "Workspace directory is unavailable" }
        val status = git(root, "status", "--porcelain=v1", "--untracked-files=normal")
        if (status.code != 0) return@withContext SlotCheckoutObservation(Clock.System.now(), warnings = listOf("Git status unavailable; checkout cleanliness is unknown"))
        val branch = git(root, "symbolic-ref", "--quiet", "--short", "HEAD")
        val default = git(root, "symbolic-ref", "--quiet", "--short", "refs/remotes/origin/HEAD")
        val ahead = git(root, "rev-list", "--count", "@{upstream}..HEAD")
        SlotCheckoutObservation(Clock.System.now(), status.text.isNotEmpty(),
            branch.text.trim().takeIf { branch.code == 0 && it.isNotEmpty() },
            default.text.trim().removePrefix("origin/").takeIf { default.code == 0 && it.isNotEmpty() },
            ahead.text.trim().toLongOrNull()?.takeIf { ahead.code == 0 },
            if (branch.code != 0) listOf("Detached HEAD or current branch is unknown") else emptyList())
    }

    private data class GitResult(val code: Int, val text: String)
    private suspend fun git(root: String, vararg args: String): GitResult = coroutineScope {
        currentCoroutineContext().ensureActive()
        val process = ProcessBuilder(listOf("git", "--no-optional-locks", "-c", "core.fsmonitor=false", "-c", "core.untrackedCache=false") + args)
            .directory(File(root)).redirectErrorStream(true).apply {
                environment()["GIT_OPTIONAL_LOCKS"] = "0"
                environment()["GIT_TERMINAL_PROMPT"] = "0"
            }.start()
        try {
            val output = async(Dispatchers.IO) {
                val saved = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                process.inputStream.use { stream ->
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        if (saved.size() < 16384) saved.write(buffer, 0, minOf(count, 16384 - saved.size()))
                    }
                }
                saved.toString(Charsets.UTF_8)
            }
            check(process.waitFor(5, TimeUnit.SECONDS)) { "Read-only Git inspection timed out" }
            GitResult(process.exitValue(), output.await())
        } finally {
            // This is only the inspector's own bounded Git subprocess, never a user's command.
            if (process.isAlive) process.destroyForcibly()
            process.inputStream.close()
        }
    }
}

@Service
@ConditionalOnProperty(name = ["gromozeka.runtime.worker.enabled"], havingValue = "true")
class CheckoutInspectionToolContributor : AiToolCallbackContributor {
    private val inspector = CheckoutInspector()
    override val callbacks = listOf(object : AiToolCallback {
        override val definition = AiToolDefinition("grz_inspect_checkout",
            "Read-only Git observations at the exact WorkspaceMount root: dirty state, current/default branch and locally known commits ahead of upstream. No fetch, network, shell scripts, cleanup or process control. Unknown values are not proof of cleanliness.",
            """{"type":"object","properties":{},"additionalProperties":false}""")
        override val metadata = AiToolMetadata(executionScope = AiToolExecutionScope.WORKSPACE,
            loadingPolicy = AiToolLoadingPolicy.ON_DEMAND, visibleToMemoryPipeline = false, logInput = false)
        override fun call(toolInput: String, context: ToolExecutionContext?): String = runBlocking {
            require(Json.parseToJsonElement(toolInput).jsonObject.isEmpty()) { "No checkout inspection arguments are accepted" }
            val trusted = requireNotNull(context)
            trusted.cancellationSignal?.throwIfCancellationRequested()
            Json.encodeToString(inspector.inspect(trusted.requiredWorkspaceRootPath()))
        }
    })
}
