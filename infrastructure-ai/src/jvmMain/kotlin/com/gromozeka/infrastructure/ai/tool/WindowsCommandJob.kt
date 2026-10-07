package com.gromozeka.infrastructure.ai.tool

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.platform.win32.BaseTSD.SIZE_T
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.platform.win32.WinNT.HANDLE
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Kernel-owned membership includes children born during cancellation, unlike a PID snapshot.
 * Names include the root start time so a reused PID cannot select an older command's job.
 * Resumable jobs have no kill-on-close limit and can be reopened after a Worker restart.
 */
internal class WindowsCommandJob private constructor(private val handle: HANDLE) : AutoCloseable {
    private val closed = AtomicBoolean()

    fun killOnClose(enabled: Boolean) {
        val limits = CommandJobLimits().apply { basic.limitFlags = if (enabled) KILL_ON_JOB_CLOSE else 0 }
        limits.write()
        checkWindows(api.SetInformationJobObject(handle, EXTENDED_LIMIT_INFORMATION, limits.pointer, limits.size()),
            "Configure command job lifetime")
    }

    fun terminateAndWait() {
        checkWindows(api.TerminateJobObject(handle, 1), "Terminate command job")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        val accounting = CommandJobAccounting()
        while (true) {
            checkWindows(api.QueryInformationJobObject(handle, BASIC_ACCOUNTING_INFORMATION,
                accounting.pointer, accounting.size(), null), "Read command job process count")
            accounting.read()
            if (accounting.activeProcesses == 0) return
            check(System.nanoTime() < deadline) { "Windows command job still contains live processes after termination" }
            Thread.sleep(10)
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            checkWindows(Kernel32.INSTANCE.CloseHandle(handle), "Close command job handle")
        }
    }

    companion object {
        private const val KILL_ON_JOB_CLOSE = 0x00002000
        private const val EXTENDED_LIMIT_INFORMATION = 9
        private const val BASIC_ACCOUNTING_INFORMATION = 1
        private const val JOB_ACCESS = 0x0002 or 0x0004 or 0x0008 // set attributes, query, terminate
        private const val ERROR_FILE_NOT_FOUND = 2
        private const val ERROR_ALREADY_EXISTS = 183
        private val api: CommandJobKernel32 by lazy {
            Native.load("kernel32", CommandJobKernel32::class.java, W32APIOptions.UNICODE_OPTIONS)
        }

        fun name(process: ProcessHandle): String {
            val started = process.info().startInstant().orElseThrow {
                IllegalStateException("Windows command start time is unavailable: ${process.pid()}")
            }
            return "Local\\Gromozeka.Command.v1.${process.pid()}.${started.toEpochMilli()}"
        }

        /** The root must still be blocked on the startup handshake: no command children yet. */
        fun assign(process: Process, workerBound: Boolean): WindowsCommandJob {
            val jobName = name(process.toHandle())
            val handle = api.CreateJobObjectW(null, WString(jobName))
            val createError = Native.getLastError()
            check(handle != null) { "Create command job failed: Windows error $createError" }
            val job = WindowsCommandJob(handle)
            try {
                check(createError != ERROR_ALREADY_EXISTS) { "Command job already exists: $jobName" }
                job.killOnClose(workerBound)
                val processHandle = Kernel32.INSTANCE.OpenProcess(
                    WinNT.PROCESS_SET_QUOTA or WinNT.PROCESS_TERMINATE, false, process.pid().toInt(),
                )
                check(processHandle != null) { "Open command for job assignment failed: Windows error ${Native.getLastError()}" }
                try {
                    checkWindows(api.AssignProcessToJobObject(handle, processHandle), "Assign command to job")
                } finally {
                    Kernel32.INSTANCE.CloseHandle(processHandle)
                }
                return job
            } catch (error: Throwable) {
                runCatching { job.close() }.onFailure(error::addSuppressed)
                throw error
            }
        }

        fun open(name: String): WindowsCommandJob? {
            val handle = api.OpenJobObjectW(JOB_ACCESS, false, WString(name))
            if (handle != null) return WindowsCommandJob(handle)
            val error = Native.getLastError()
            check(error == ERROR_FILE_NOT_FOUND) { "Open command job failed: Windows error $error" }
            return null
        }

        private fun checkWindows(success: Boolean, operation: String) {
            check(success) { "$operation failed: Windows error ${Native.getLastError()}" }
        }
    }
}

internal class WindowsJobLifetimeBinding(private val job: WindowsCommandJob) : LocalWorkerLifetimeBinding {
    private val closed = AtomicBoolean()

    override fun disarm() {
        if (!closed.compareAndSet(false, true)) return
        try { job.killOnClose(false) } finally { job.close() }
    }

    override fun terminateCommand() {
        if (!closed.compareAndSet(false, true)) return
        try { job.terminateAndWait() } finally { job.close() }
    }
}

internal interface CommandJobKernel32 : StdCallLibrary {
    fun CreateJobObjectW(attributes: WinBase.SECURITY_ATTRIBUTES?, name: WString): HANDLE?
    fun OpenJobObjectW(access: Int, inheritHandle: Boolean, name: WString): HANDLE?
    fun AssignProcessToJobObject(job: HANDLE, process: HANDLE): Boolean
    fun SetInformationJobObject(job: HANDLE, informationClass: Int, information: Pointer, size: Int): Boolean
    fun QueryInformationJobObject(job: HANDLE, informationClass: Int, information: Pointer, size: Int, returned: IntByReference?): Boolean
    fun TerminateJobObject(job: HANDLE, exitCode: Int): Boolean
}

@Structure.FieldOrder("processTime", "jobTime", "limitFlags", "minimumWorkingSet", "maximumWorkingSet",
    "activeProcessLimit", "affinity", "priorityClass", "schedulingClass")
internal class CommandJobBasicLimits : Structure() {
    @JvmField var processTime: Long = 0
    @JvmField var jobTime: Long = 0
    @JvmField var limitFlags: Int = 0
    @JvmField var minimumWorkingSet = SIZE_T()
    @JvmField var maximumWorkingSet = SIZE_T()
    @JvmField var activeProcessLimit: Int = 0
    @JvmField var affinity = SIZE_T()
    @JvmField var priorityClass: Int = 0
    @JvmField var schedulingClass: Int = 0
}

@Structure.FieldOrder("basic", "io", "processMemory", "jobMemory", "peakProcessMemory", "peakJobMemory")
internal class CommandJobLimits : Structure() {
    @JvmField var basic = CommandJobBasicLimits()
    @JvmField var io = WinNT.IO_COUNTERS()
    @JvmField var processMemory = SIZE_T()
    @JvmField var jobMemory = SIZE_T()
    @JvmField var peakProcessMemory = SIZE_T()
    @JvmField var peakJobMemory = SIZE_T()
}

@Structure.FieldOrder("totalUserTime", "totalKernelTime", "periodUserTime", "periodKernelTime",
    "pageFaults", "totalProcesses", "activeProcesses", "terminatedProcesses")
internal class CommandJobAccounting : Structure() {
    @JvmField var totalUserTime: Long = 0
    @JvmField var totalKernelTime: Long = 0
    @JvmField var periodUserTime: Long = 0
    @JvmField var periodKernelTime: Long = 0
    @JvmField var pageFaults: Int = 0
    @JvmField var totalProcesses: Int = 0
    @JvmField var activeProcesses: Int = 0
    @JvmField var terminatedProcesses: Int = 0
}
