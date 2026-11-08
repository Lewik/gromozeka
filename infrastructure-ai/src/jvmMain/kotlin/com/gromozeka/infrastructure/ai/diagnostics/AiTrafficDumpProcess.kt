package com.gromozeka.infrastructure.ai.diagnostics

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.TimeUnit

class AiTrafficDumpProcess private constructor(private val delegate: Process, private val trace: AiTrafficRecording) : Process() {
    private val stdin = trace.output("stdin", delegate.outputStream)
    private val stdout = trace.input("stdout", delegate.inputStream)
    private val stderr = trace.input("stderr", delegate.errorStream)
    override fun getOutputStream() = stdin
    override fun getInputStream() = stdout
    override fun getErrorStream() = stderr
    override fun waitFor() = delegate.waitFor()
    override fun waitFor(timeout: Long, unit: TimeUnit) = delegate.waitFor(timeout, unit)
    override fun exitValue() = delegate.exitValue()
    override fun destroy() { trace.event("process.destroy"); delegate.destroy() }
    override fun destroyForcibly(): Process { trace.event("process.kill"); delegate.destroyForcibly(); return this }
    override fun isAlive() = delegate.isAlive
    override fun pid() = delegate.pid()
    override fun toHandle() = delegate.toHandle()
    override fun supportsNormalTermination() = delegate.supportsNormalTermination()

    companion object {
        fun capture(process: Process, provider: String, command: List<String>, systemPrompt: String? = null): Process {
            val trace = DangerousAiTrafficDump.open(provider, "cli") ?: return process
            trace.event("process.started", buildJsonObject {
                put("pid", process.pid())
                put("command", JsonArray(command.map(::JsonPrimitive)))
            })
            systemPrompt?.let { trace.text("system-prompt", it) }
            process.onExit().whenComplete { exited, error ->
                if (error != null) trace.failure(error)
                else trace.event("process.exited", buildJsonObject { put("status", exited.exitValue()) })
            }
            return AiTrafficDumpProcess(process, trace)
        }
    }
}
