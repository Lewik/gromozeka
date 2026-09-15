package com.gromozeka.worker

import com.gromozeka.domain.service.ComputerUseAction
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import com.gromozeka.domain.service.ConversationRuntimeWorkerSessionId
import com.sun.jna.Native
import com.sun.jna.platform.win32.Advapi32
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.platform.win32.Winsvc
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object WindowsWorkerServiceSmoke {
    @JvmStatic
    fun main(args: Array<String>) {
        check(System.getProperty("os.name").startsWith("Windows"))
        when (args[0]) {
            "service" -> service(args[1], Path.of(args[2]))
            "deny" -> deny(args[1], args[2].toInt(), args[3], Path.of(args[4]))
            else -> error("Unknown Windows service smoke mode")
        }
    }

    private fun service(name: String, result: Path) {
        System.setErr(java.io.PrintStream(Files.newOutputStream(result.resolveSibling("service-error.log")), true, Charsets.UTF_8))
        WindowsWorkerService(name).run { started ->
            val stop = CountDownLatch(1)
            WindowsDesktopSessionManager().use { manager ->
                started { stop.countDown() }
                val backend = WindowsDesktopComputerUseBackend(manager)
                var desktopResult = "no-console"
                if (WindowsDesktopNative.activeSession() != null) {
                    val connection = awaitConnection(manager)
                    val display = backend.targets().first()
                    val identity = ConversationRuntimeWorkerIdentity(
                        ConversationRuntimeWorkerId("windows-smoke"), ConversationRuntimeWorkerSessionId("one-worker-process"),
                    )
                    val observation = backend.capture(identity, display.id, 1024)
                    check(observation.png.size > 8)
                    check(observation.reference.workerId == identity.workerId)
                    backend.execute(observation.reference, listOf(ComputerUseAction.Wait(1))) {}
                    val child = ProcessHandle.current().children().use { children ->
                        children.findFirst().orElseThrow()
                    }
                    check(child.destroyForcibly())
                    val restarted = awaitConnection(manager, connection.generation)
                    check(restarted.generation != connection.generation)
                    val rejected = runCatching {
                        backend.execute(observation.reference, listOf(ComputerUseAction.Wait(1))) {}
                    }.exceptionOrNull()
                    check(rejected is IllegalArgumentException) { "Old desktop observation was not rejected" }
                    val newDisplay = backend.targets().first()
                    backend.capture(identity, newDisplay.id, 1024)
                    desktopResult = "verified"
                }
                Files.writeString(result, "pid=${ProcessHandle.current().pid()}\ndesktop=$desktopResult\n")
                stop.await()
            }
        }
    }

    private fun awaitConnection(manager: WindowsDesktopSessionManager, previousGeneration: String? = null): DesktopHelperConnection {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
        var failure: Throwable? = null
        while (System.nanoTime() < deadline) {
            val connection = runCatching { manager.current() }.onFailure { failure = it }.getOrNull()
            if (connection != null && connection.generation != previousGeneration) return connection
            Thread.sleep(100)
        }
        throw IllegalStateException("Windows desktop helper did not connect", failure)
    }

    private fun deny(service: String, pid: Int, user: String, passwordFile: Path) {
        val token = WinNT.HANDLEByReference()
        windowsCheck(Advapi32.INSTANCE.LogonUser(
            user, ".", Files.readString(passwordFile).trim(), 2, 0, token,
        ), "Create standard-user verification token")
        try {
            windowsCheck(Advapi32.INSTANCE.ImpersonateLoggedOnUser(token.value), "Use standard-user token")
            try {
                val manager = checkNotNull(Advapi32.INSTANCE.OpenSCManager(null, null, Winsvc.SC_MANAGER_CONNECT))
                try {
                    val stop = Advapi32.INSTANCE.OpenService(manager, service, Winsvc.SERVICE_STOP)
                    check(stop == null && Native.getLastError() == 5) { "Standard user can stop the service" }
                    val configure = Advapi32.INSTANCE.OpenService(manager, service, Winsvc.SERVICE_CHANGE_CONFIG)
                    check(configure == null && Native.getLastError() == 5) { "Standard user can reconfigure the service" }
                    val query = checkNotNull(Advapi32.INSTANCE.OpenService(manager, service, Winsvc.SERVICE_QUERY_STATUS))
                    Advapi32.INSTANCE.CloseServiceHandle(query)
                } finally {
                    Advapi32.INSTANCE.CloseServiceHandle(manager)
                }
                val process = Kernel32.INSTANCE.OpenProcess(WinNT.PROCESS_TERMINATE, false, pid)
                check(process == null && Native.getLastError() == 5) { "Standard user can terminate the service process" }
            } finally {
                windowsCheck(Advapi32.INSTANCE.RevertToSelf(), "Restore administrator token")
            }
        } finally {
            Kernel32.INSTANCE.CloseHandle(token.value)
        }
        println("Standard user cannot stop, reconfigure, or terminate the Worker service")
    }
}
