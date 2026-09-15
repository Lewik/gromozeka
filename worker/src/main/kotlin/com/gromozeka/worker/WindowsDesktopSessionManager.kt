package com.gromozeka.worker

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.slf4j.LoggerFactory

internal class WindowsDesktopSessionManager : DesktopSessionProvider {
    private val closed = AtomicBoolean()
    private val active = AtomicReference<ActiveDesktop?>()
    private val supervisor = Executors.newSingleThreadScheduledExecutor {
        Thread(it, "windows-desktop-supervisor").apply { isDaemon = true }
    }
    @Volatile private var unavailable = "No interactive Windows desktop is connected"
    private var lastFailure: String? = null

    init {
        supervisor.scheduleWithFixedDelay(::reconcile, 0, 1, TimeUnit.SECONDS)
    }

    override fun current(): DesktopHelperConnection = active.get()?.connection ?: error(unavailable)

    private fun reconcile() {
        if (closed.get()) return
        try {
            val session = WindowsDesktopNative.activeSession()
            val existing = active.get()
            if (existing != null && existing.session == session && existing.process.alive) return
            active.getAndSet(null)?.close()
            if (session == null) {
                unavailable = "No user is logged in to the Windows console desktop"
                return
            }
            unavailable = "Windows desktop helper is starting; retry shortly"
            val pipeName = WindowsDesktopPipe.name()
            val pipe = WindowsDesktopPipe.create(pipeName, session.userSid)
            var process: WindowsDesktopProcess? = null
            try {
                process = WindowsDesktopNative.launchHelper(session, pipeName)
                val child = process
                val validateSession = {
                    check(!closed.get() && child.alive) { "Windows desktop helper stopped" }
                    check(WindowsDesktopNative.activeSession() == session) {
                        "Windows user session changed; capture a fresh observation"
                    }
                }
                pipe.accept(child.pid, validateSession)
                val connection = DesktopHelperConnection(pipe, validateSession = validateSession)
                val connected = ActiveDesktop(session, child, connection)
                active.set(connected)
                if (closed.get()) active.getAndSet(null)?.close()
                lastFailure = null
            } catch (error: Throwable) {
                pipe.close()
                process?.close()
                throw error
            }
        } catch (error: Exception) {
            active.getAndSet(null)?.close()
            unavailable = "Windows desktop helper unavailable: ${error.message}"
            if (!closed.get() && unavailable != lastFailure) {
                logger.warn("{}", unavailable)
                lastFailure = unavailable
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        active.getAndSet(null)?.close()
        supervisor.shutdownNow()
        supervisor.awaitTermination(5, TimeUnit.SECONDS)
    }

    private data class ActiveDesktop(
        val session: WindowsDesktopSession,
        val process: WindowsDesktopProcess,
        val connection: DesktopHelperConnection,
    ) : AutoCloseable {
        override fun close() {
            connection.close()
            process.close()
        }
    }

    private companion object {
        val logger = LoggerFactory.getLogger(WindowsDesktopSessionManager::class.java)
    }
}

internal fun runWindowsDesktopHelper(arguments: List<String>) {
    require(arguments.size == 2) { "Desktop helper requires a service pipe and parent process ID" }
    val parentPid = arguments[1].toLong()
    require(parentPid > 0)
    WindowsDesktopPipe.connect(arguments[0], parentPid).use { pipe ->
        try {
            serveDesktopHelper(pipe, JvmComputerUseBackend(JvmComputerUsePlatformAccess())) {
                WindowsDesktopNative.requireInteractiveDesktop()
            }
        } catch (_: java.io.IOException) {
            return
        }
    }
}
