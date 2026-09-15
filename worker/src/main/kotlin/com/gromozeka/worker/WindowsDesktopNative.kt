package com.gromozeka.worker

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.platform.win32.Advapi32
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.BaseTSD.SIZE_T
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.platform.win32.WinNT.HANDLE
import com.sun.jna.platform.win32.WinNT.HANDLEByReference
import com.sun.jna.platform.win32.WinDef.ULONGByReference
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal fun windowsCheck(success: Boolean, operation: String) {
    if (!success) throw IOException("$operation failed (Windows error ${Native.getLastError()})")
}

internal data class WindowsDesktopSession(val id: Int, val userSid: String, val logonId: Long)

internal object WindowsDesktopNative {
    val kernel: DesktopKernel32 by lazy { Native.load("kernel32", DesktopKernel32::class.java, W32APIOptions.UNICODE_OPTIONS) }
    val security: DesktopAdvapi32 by lazy { Native.load("advapi32", DesktopAdvapi32::class.java, W32APIOptions.UNICODE_OPTIONS) }
    val terminal: DesktopWtsapi32 by lazy { Native.load("wtsapi32", DesktopWtsapi32::class.java) }
    val environment: DesktopUserenv by lazy { Native.load("userenv", DesktopUserenv::class.java) }
    private val desktop: DesktopUser32 by lazy { Native.load("user32", DesktopUser32::class.java, W32APIOptions.UNICODE_OPTIONS) }

    fun activeSession(): WindowsDesktopSession? {
        val sessionId = kernel.WTSGetActiveConsoleSessionId()
        if (sessionId == -1 || sessionId == 0) return null
        val token = HANDLEByReference()
        if (!terminal.WTSQueryUserToken(sessionId, token)) {
            val error = Native.getLastError()
            if (error == 1008 || error == 1312 || error == 7022) return null
            throw IOException("Cannot query interactive Windows user (Windows error $error)")
        }
        return try {
            val userSid = tokenSid(token.value)
            val statistics = WindowsTokenStatistics()
            windowsCheck(Advapi32.INSTANCE.GetTokenInformation(
                token.value, WinNT.TOKEN_INFORMATION_CLASS.TokenStatistics, statistics,
                statistics.size(), IntByReference(),
            ), "Read Windows logon identity")
            WindowsDesktopSession(sessionId, userSid, statistics.authenticationId)
        } finally {
            Kernel32.INSTANCE.CloseHandle(token.value)
        }
    }

    fun tokenSid(token: HANDLE): String {
        val size = IntByReference()
        Advapi32.INSTANCE.GetTokenInformation(token, WinNT.TOKEN_INFORMATION_CLASS.TokenUser, null, 0, size)
        val user = WinNT.TOKEN_USER(size.value)
        windowsCheck(Advapi32.INSTANCE.GetTokenInformation(
            token, WinNT.TOKEN_INFORMATION_CLASS.TokenUser, user, size.value, size,
        ), "Read Windows user identity")
        return Advapi32Util.convertSidToStringSid(user.User.Sid)
    }

    fun requireInteractiveDesktop() {
        val handle = desktop.OpenInputDesktop(0, false, 0x0001)
            ?: error("Windows desktop is locked or unavailable")
        try {
            Memory(1024).use { name ->
                windowsCheck(desktop.GetUserObjectInformationW(handle, 2, name, name.size().toInt(), IntByReference()),
                    "Read interactive desktop name")
                check(name.getWideString(0).equals("Default", ignoreCase = true)) {
                    "Windows secure desktop is active; unlock the desktop before using Computer Use"
                }
            }
        } finally {
            desktop.CloseDesktop(handle)
        }
    }

    fun launchHelper(session: WindowsDesktopSession, pipe: String): WindowsDesktopProcess {
        val token = HANDLEByReference()
        windowsCheck(terminal.WTSQueryUserToken(session.id, token), "Acquire interactive Windows user token")
        val environmentBlock = PointerByReference()
        val process = WinBase.PROCESS_INFORMATION()
        var job: HANDLE? = null
        try {
            check(tokenSid(token.value) == session.userSid) { "Windows user changed during helper startup" }
            windowsCheck(environment.CreateEnvironmentBlock(environmentBlock, token.value, false), "Create user environment")
            val java = Path.of(System.getProperty("java.home"), "bin", "javaw.exe").toString()
            val command = listOf(java, "-cp", System.getProperty("java.class.path")) +
                workerJavaMainArguments() + listOf("windows-desktop-helper", pipe, ProcessHandle.current().pid().toString())
            val startup = WinBase.STARTUPINFO().apply { lpDesktop = "winsta0\\default" }
            windowsCheck(security.CreateProcessAsUserW(
                token.value, WString(java), (command.joinToString(" ", transform = ::quoteWindowsArgument) + '\u0000').toCharArray(),
                null, null, false, 0x00000400 or 0x00000004 or 0x08000000,
                environmentBlock.value, WString(Path.of(System.getProperty("java.home")).toString()), startup, process,
            ), "Start desktop helper")
            job = kernel.CreateJobObjectW(null, null)
            check(job != null) { "Cannot create desktop helper job" }
            val limits = WindowsJobLimits().apply { basic.limitFlags = 0x00002000 }
            limits.write()
            windowsCheck(kernel.SetInformationJobObject(job, 9, limits.pointer, limits.size()), "Configure desktop helper lifetime")
            windowsCheck(kernel.AssignProcessToJobObject(job, process.hProcess), "Attach desktop helper to Worker lifetime")
            check(kernel.ResumeThread(process.hThread) != -1) { "Cannot resume desktop helper" }
            return WindowsDesktopProcess(process.hProcess, job, process.dwProcessId.toLong())
        } catch (error: Throwable) {
            process.hProcess?.let {
                Kernel32.INSTANCE.TerminateProcess(it, 1)
                Kernel32.INSTANCE.CloseHandle(it)
            }
            job?.let { Kernel32.INSTANCE.CloseHandle(it) }
            throw error
        } finally {
            process.hThread?.let { Kernel32.INSTANCE.CloseHandle(it) }
            environmentBlock.value?.let { environment.DestroyEnvironmentBlock(it) }
            Kernel32.INSTANCE.CloseHandle(token.value)
        }
    }
}

internal fun workerJavaMainArguments(): List<String> =
    if (runCatching {
            Class.forName("org.springframework.boot.loader.launch.JarLauncher", false, ClassLoader.getSystemClassLoader())
        }.isSuccess
    ) listOf("org.springframework.boot.loader.launch.JarLauncher")
    else listOf("com.gromozeka.worker.GromozekaWorkerMainKt")

internal fun quoteWindowsArgument(value: String): String {
    require('\u0000' !in value && '\r' !in value && '\n' !in value)
    return buildString {
        append('"')
        var backslashes = 0
        for (character in value) {
            if (character == '\\') {
                backslashes++
            } else {
                repeat(if (character == '"') backslashes * 2 + 1 else backslashes) { append('\\') }
                append(character)
                backslashes = 0
            }
        }
        repeat(backslashes * 2) { append('\\') }
        append('"')
    }
}

internal class WindowsDesktopProcess(private val process: HANDLE, private val job: HANDLE, val pid: Long) : AutoCloseable {
    private val closed = AtomicBoolean()
    val alive: Boolean get() = !closed.get() && Kernel32.INSTANCE.WaitForSingleObject(process, 0) == 258
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        Kernel32.INSTANCE.WaitForSingleObject(process, 1500)
        Kernel32.INSTANCE.CloseHandle(job)
        Kernel32.INSTANCE.CloseHandle(process)
    }
}

internal class WindowsDesktopPipe private constructor(private val handle: HANDLE) : DesktopHelperChannel {
    private val closed = AtomicBoolean()
    private val writeLock = Any()

    override fun send(message: String) = synchronized(writeLock) {
        check(!closed.get()) { "Desktop helper pipe is closed" }
        val bytes = message.toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..MAX_FRAME_BYTES)
        val frame = ByteBuffer.allocate(bytes.size + 4).putInt(bytes.size).put(bytes).array()
        var offset = 0
        while (offset < frame.size) {
            val chunk = frame.copyOfRange(offset, minOf(offset + 65_536, frame.size))
            val written = IntByReference()
            windowsCheck(Kernel32.INSTANCE.WriteFile(handle, chunk, chunk.size, written, null), "Write desktop helper pipe")
            check(written.value > 0) { "Desktop helper pipe stopped accepting data" }
            offset += written.value
        }
    }

    override fun receive(interruptionCheck: () -> Unit): String {
        val size = ByteBuffer.wrap(readExactly(4, interruptionCheck)).int
        require(size in 1..MAX_FRAME_BYTES) { "Invalid desktop helper frame size" }
        return readExactly(size, interruptionCheck).toString(Charsets.UTF_8)
    }

    private fun readExactly(size: Int, interruptionCheck: () -> Unit): ByteArray {
        val result = ByteArray(size)
        var offset = 0
        while (offset < size) {
            interruptionCheck()
            if (closed.get()) throw EOFException("Desktop helper pipe closed")
            val available = IntByReference()
            windowsCheck(Kernel32.INSTANCE.PeekNamedPipe(handle, null, 0, null, available, null), "Read desktop helper pipe state")
            if (available.value == 0) {
                Thread.sleep(20)
                continue
            }
            val chunk = ByteArray(minOf(available.value, size - offset, 65_536))
            val read = IntByReference()
            windowsCheck(Kernel32.INSTANCE.ReadFile(handle, chunk, chunk.size, read, null), "Read desktop helper pipe")
            if (read.value == 0) throw EOFException("Desktop helper pipe ended")
            chunk.copyInto(result, offset, 0, read.value)
            offset += read.value
        }
        return result
    }

    fun accept(expectedPid: Long, interruptionCheck: () -> Unit) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (true) {
            interruptionCheck()
            check(System.nanoTime() < deadline) { "Desktop helper connection timed out" }
            if (Kernel32.INSTANCE.ConnectNamedPipe(handle, null)) break
            when (Native.getLastError()) {
                535 -> break
                536 -> Thread.sleep(20)
                else -> windowsCheck(false, "Accept desktop helper pipe")
            }
        }
        val clientPid = ULONGByReference()
        windowsCheck(Kernel32.INSTANCE.GetNamedPipeClientProcessId(handle, clientPid), "Verify desktop helper process")
        check(clientPid.value.toLong() == expectedPid) { "Unexpected desktop helper pipe client" }
        windowsCheck(Kernel32.INSTANCE.SetNamedPipeHandleState(handle, IntByReference(0), null, null), "Set desktop helper pipe mode")
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) Kernel32.INSTANCE.CloseHandle(handle)
    }

    companion object {
        private const val MAX_FRAME_BYTES = 8 * 1024 * 1024
        fun name(): String = "\\\\.\\pipe\\gromozeka-desktop-${UUID.randomUUID()}"

        fun create(name: String, userSid: String): WindowsDesktopPipe {
            require(userSid.matches(Regex("S-[0-9-]+")))
            val descriptor = PointerByReference()
            windowsCheck(WindowsDesktopNative.security.ConvertStringSecurityDescriptorToSecurityDescriptorW(
                WString("D:P(A;;GA;;;SY)(A;;GA;;;BA)(A;;GRGW;;;$userSid)"), 1, descriptor, null,
            ), "Create desktop helper pipe permissions")
            try {
                val attributes = WinBase.SECURITY_ATTRIBUTES().apply { lpSecurityDescriptor = descriptor.value }
                val handle = Kernel32.INSTANCE.CreateNamedPipe(
                    name, 0x00000003 or 0x00080000, 0x00000001 or 0x00000008,
                    1, 65_536, 65_536, 0, attributes,
                )
                check(handle != WinBase.INVALID_HANDLE_VALUE) { "Cannot create desktop helper pipe (Windows error ${Native.getLastError()})" }
                return WindowsDesktopPipe(handle)
            } finally {
                Kernel32.INSTANCE.LocalFree(descriptor.value)
            }
        }

        fun connect(name: String, expectedServerPid: Long): WindowsDesktopPipe {
            require(name.startsWith("\\\\.\\pipe\\gromozeka-desktop-"))
            val handle = Kernel32.INSTANCE.CreateFile(
                name, WinNT.GENERIC_READ or WinNT.GENERIC_WRITE, 0, null,
                WinNT.OPEN_EXISTING, 0x00100000 or 0x00010000, null,
            )
            check(handle != WinBase.INVALID_HANDLE_VALUE) { "Cannot connect to desktop helper service" }
            try {
                val serverPid = ULONGByReference()
                windowsCheck(Kernel32.INSTANCE.GetNamedPipeServerProcessId(handle, serverPid), "Verify desktop helper service")
                check(serverPid.value.toLong() == expectedServerPid) { "Unexpected desktop helper pipe server" }
                return WindowsDesktopPipe(handle)
            } catch (error: Throwable) {
                Kernel32.INSTANCE.CloseHandle(handle)
                throw error
            }
        }
    }
}

internal interface DesktopWtsapi32 : StdCallLibrary {
    fun WTSQueryUserToken(sessionId: Int, token: HANDLEByReference): Boolean
}

internal interface DesktopUserenv : StdCallLibrary {
    fun CreateEnvironmentBlock(environment: PointerByReference, token: HANDLE, inherit: Boolean): Boolean
    fun DestroyEnvironmentBlock(environment: Pointer): Boolean
}

internal interface DesktopKernel32 : StdCallLibrary {
    fun WTSGetActiveConsoleSessionId(): Int
    fun CreateJobObjectW(attributes: WinBase.SECURITY_ATTRIBUTES?, name: WString?): HANDLE?
    fun SetInformationJobObject(job: HANDLE, informationClass: Int, information: Pointer, size: Int): Boolean
    fun AssignProcessToJobObject(job: HANDLE, process: HANDLE): Boolean
    fun ResumeThread(thread: HANDLE): Int
}

internal interface DesktopAdvapi32 : StdCallLibrary {
    fun CreateProcessAsUserW(
        token: HANDLE, application: WString, command: CharArray,
        processAttributes: WinBase.SECURITY_ATTRIBUTES?, threadAttributes: WinBase.SECURITY_ATTRIBUTES?,
        inheritHandles: Boolean, flags: Int, environment: Pointer?, directory: WString?,
        startup: WinBase.STARTUPINFO, process: WinBase.PROCESS_INFORMATION,
    ): Boolean
    fun ConvertStringSecurityDescriptorToSecurityDescriptorW(
        text: WString, revision: Int, descriptor: PointerByReference, size: IntByReference?,
    ): Boolean
}

internal interface DesktopUser32 : StdCallLibrary {
    fun OpenInputDesktop(flags: Int, inherit: Boolean, access: Int): HANDLE?
    fun GetUserObjectInformationW(handle: HANDLE, index: Int, information: Pointer, size: Int, needed: IntByReference): Boolean
    fun CloseDesktop(handle: HANDLE): Boolean
}

@Structure.FieldOrder("tokenId", "authenticationId", "expirationTime", "tokenType", "impersonationLevel",
    "dynamicCharged", "dynamicAvailable", "groupCount", "privilegeCount", "modifiedId")
internal class WindowsTokenStatistics : Structure() {
    @JvmField var tokenId: Long = 0
    @JvmField var authenticationId: Long = 0
    @JvmField var expirationTime: Long = 0
    @JvmField var tokenType: Int = 0
    @JvmField var impersonationLevel: Int = 0
    @JvmField var dynamicCharged: Int = 0
    @JvmField var dynamicAvailable: Int = 0
    @JvmField var groupCount: Int = 0
    @JvmField var privilegeCount: Int = 0
    @JvmField var modifiedId: Long = 0
}

@Structure.FieldOrder("processTime", "jobTime", "limitFlags", "minimumWorkingSet", "maximumWorkingSet",
    "activeProcessLimit", "affinity", "priorityClass", "schedulingClass")
internal class WindowsJobBasicLimits : Structure() {
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
internal class WindowsJobLimits : Structure() {
    @JvmField var basic = WindowsJobBasicLimits()
    @JvmField var io = WinNT.IO_COUNTERS()
    @JvmField var processMemory = SIZE_T()
    @JvmField var jobMemory = SIZE_T()
    @JvmField var peakProcessMemory = SIZE_T()
    @JvmField var peakJobMemory = SIZE_T()
}
