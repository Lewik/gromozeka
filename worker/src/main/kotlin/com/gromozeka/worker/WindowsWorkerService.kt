package com.gromozeka.worker

import com.sun.jna.platform.win32.Advapi32
import com.sun.jna.platform.win32.Winsvc
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.platform.win32.Winsvc.SERVICE_STATUS
import com.sun.jna.platform.win32.Winsvc.SERVICE_STATUS_HANDLE
import com.sun.jna.platform.win32.Winsvc.SERVICE_TABLE_ENTRY
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal class WindowsWorkerService(private val name: String = "GromozekaWorker") {
    private val statusLock = Any()
    private var handle: SERVICE_STATUS_HANDLE? = null
    private var state = Winsvc.SERVICE_START_PENDING
    private var checkpoint = 0
    private var stopRequested = false
    private var stopAction: (() -> Unit)? = null

    private val control = Winsvc.HandlerEx { code, _, _, _ ->
        when (code) {
            Winsvc.SERVICE_CONTROL_STOP, Winsvc.SERVICE_CONTROL_SHUTDOWN -> requestStop()
            Winsvc.SERVICE_CONTROL_INTERROGATE -> synchronized(statusLock) { report() }
        }
        0
    }

    fun run(worker: (started: (stop: () -> Unit) -> Unit) -> Unit) {
        check(System.getProperty("os.name").startsWith("Windows"))
        val failure = AtomicReference<Throwable?>()
        val serviceMain = Winsvc.SERVICE_MAIN_FUNCTION { _, _ ->
            val pending = Executors.newSingleThreadScheduledExecutor {
                Thread(it, "windows-service-status").apply { isDaemon = true }
            }
            try {
                synchronized(statusLock) {
                    handle = checkNotNull(Advapi32.INSTANCE.RegisterServiceCtrlHandlerEx(name, control, null)) {
                        "Cannot register Windows service control handler"
                    }
                    report()
                }
                pending.scheduleWithFixedDelay({
                    synchronized(statusLock) {
                        if (state == Winsvc.SERVICE_START_PENDING || state == Winsvc.SERVICE_STOP_PENDING) report()
                    }
                }, 5, 5, TimeUnit.SECONDS)
                worker(::started)
                synchronized(statusLock) {
                    check(stopRequested) { "Worker exited without a Windows service stop request" }
                }
            } catch (error: Throwable) {
                failure.set(error)
                error.printStackTrace(System.err)
                System.getenv("GROMOZEKA_HOME")?.let { home ->
                    runCatching {
                        val log = java.nio.file.Path.of(home, "logs", "worker-service-error.log")
                        java.nio.file.Files.createDirectories(log.parent)
                        java.nio.file.Files.writeString(log, "${java.time.Instant.now()}\n${error.stackTraceToString().take(65_536)}")
                    }
                }
            } finally {
                pending.shutdownNow()
                synchronized(statusLock) {
                    state = Winsvc.SERVICE_STOPPED
                    report(failed = failure.get() != null)
                }
            }
        }
        @Suppress("UNCHECKED_CAST")
        val table = SERVICE_TABLE_ENTRY().toArray(2) as Array<SERVICE_TABLE_ENTRY>
        table[0].lpServiceName = name
        table[0].lpServiceProc = serviceMain
        table[0].write()
        table[1].write()
        windowsCheck(Advapi32.INSTANCE.StartServiceCtrlDispatcher(table), "Connect Worker to Windows Service Control Manager")
        failure.get()?.let { throw IllegalStateException("Windows Worker service failed", it) }
    }

    private fun started(stop: () -> Unit) = synchronized(statusLock) {
        stopAction = stop
        if (stopRequested) {
            Thread(stop, "windows-worker-stop").start()
        } else {
            state = Winsvc.SERVICE_RUNNING
            report()
        }
    }

    private fun requestStop() = synchronized(statusLock) {
        if (!stopRequested && state != Winsvc.SERVICE_STOPPED) {
            stopRequested = true
            state = Winsvc.SERVICE_STOP_PENDING
            report()
            stopAction?.let { Thread(it, "windows-worker-stop").start() }
        }
    }

    private fun report(failed: Boolean = false) {
        val serviceHandle = handle ?: return
        val pending = state == Winsvc.SERVICE_START_PENDING || state == Winsvc.SERVICE_STOP_PENDING
        val status = SERVICE_STATUS().apply {
            dwServiceType = WinNT.SERVICE_WIN32_OWN_PROCESS
            dwCurrentState = state
            dwControlsAccepted = if (state == Winsvc.SERVICE_RUNNING) {
                Winsvc.SERVICE_ACCEPT_STOP or Winsvc.SERVICE_ACCEPT_SHUTDOWN
            } else 0
            dwWin32ExitCode = if (failed) 1066 else 0
            dwServiceSpecificExitCode = if (failed) 1 else 0
            dwCheckPoint = if (pending) ++checkpoint else 0
            dwWaitHint = if (pending) 120_000 else 0
        }
        windowsCheck(Advapi32.INSTANCE.SetServiceStatus(serviceHandle, status), "Report Windows Worker service status")
    }
}
