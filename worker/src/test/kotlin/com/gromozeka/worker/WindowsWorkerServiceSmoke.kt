package com.gromozeka.worker

import com.gromozeka.domain.service.ComputerUseAction
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.ConversationRuntimeWorkerIdentity
import com.gromozeka.domain.service.ConversationRuntimeWorkerSessionId
import com.gromozeka.domain.tool.TOOL_CONTEXT_WORKER_ID
import com.gromozeka.domain.tool.ToolExecutionContext
import com.gromozeka.domain.tool.worker.CaptureScreenshotRequest
import com.gromozeka.infrastructure.ai.tool.worker.GrzCaptureScreenshotToolImpl
import com.sun.jna.Native
import com.sun.jna.platform.win32.Advapi32
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.platform.win32.Winsvc
import java.nio.file.Files
import java.nio.file.Path
import java.awt.Color
import java.awt.GridLayout
import java.awt.Rectangle
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities

object WindowsWorkerServiceSmoke {
    @JvmStatic
    fun main(args: Array<String>) {
        check(System.getProperty("os.name").startsWith("Windows"))
        when (args[0]) {
            "service" -> service(args[1], Path.of(args[2]))
            "deny" -> deny(args[1], args[2].toInt(), args[3], Path.of(args[4]))
            "desktop-fixture" -> desktopFixture(Path.of(args[1]))
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
                val session = checkNotNull(WindowsDesktopNative.activeSession()) {
                    "Windows service verification requires a logged-in console user"
                }
                val fixtureReady = result.resolveSibling("desktop-fixture-${UUID.randomUUID()}.txt")
                WindowsDesktopNative.launchProcess(
                    session, listOf(WindowsWorkerServiceSmoke::class.java.name, "desktop-fixture", fixtureReady.toString()),
                ).use { fixtureProcess ->
                    val connection = awaitConnection(manager)
                    val fixtureBounds = awaitFixture(fixtureReady)
                    val display = backend.targets().first()
                    val identity = ConversationRuntimeWorkerIdentity(
                        ConversationRuntimeWorkerId("windows-smoke"), ConversationRuntimeWorkerSessionId("one-worker-process"),
                    )
                    val observation = backend.capture(identity, display.id, 1024)
                    check(observation.png.size > 8)
                    check(observation.reference.workerId == identity.workerId)
                    verifyScreenshotTool(backend, identity, fixtureBounds)
                    backend.execute(observation.reference, listOf(ComputerUseAction.Wait(1))) {}
                    val child = ProcessHandle.current().children().use { children ->
                        children.filter { it.pid() != fixtureProcess.pid }.findFirst().orElseThrow()
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
                    verifyScreenshotTool(backend, identity, fixtureBounds)
                }
                Files.writeString(result, "pid=${ProcessHandle.current().pid()}\ndesktop=verified\nscreenshot=fixture-verified\n")
                stop.await()
            }
        }
    }

    private val fixtureColors = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)

    private fun desktopFixture(ready: Path) {
        SwingUtilities.invokeAndWait {
            val frame = JFrame("Gromozeka service screenshot verification").apply {
                isUndecorated = true
                layout = GridLayout(2, 2)
                fixtureColors.forEach { color -> add(JPanel().apply { background = color }) }
                setSize(400, 240)
                setLocationRelativeTo(null)
                isAlwaysOnTop = true
                isVisible = true
                toFront()
            }
            Files.writeString(ready, "${frame.x},${frame.y},${frame.width},${frame.height}")
        }
    }

    private fun awaitFixture(ready: Path): Rectangle {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (System.nanoTime() < deadline) {
            if (Files.exists(ready)) {
                val values = Files.readString(ready).split(',').mapNotNull(String::toIntOrNull)
                if (values.size == 4) return Rectangle(values[0], values[1], values[2], values[3])
            }
            Thread.sleep(100)
        }
        error("Interactive screenshot fixture did not start")
    }

    private fun verifyScreenshotTool(
        backend: ComputerUseBackend,
        identity: ConversationRuntimeWorkerIdentity,
        fixture: Rectangle,
    ) {
        val screenshots = ConversationRuntimeWorkerConfiguration().desktopScreenshotCapture(backend)
        val tool = GrzCaptureScreenshotToolImpl(identity.workerId.value, screenshots)
        val context = ToolExecutionContext(mapOf(TOOL_CONTEXT_WORKER_ID to identity.workerId.value))
        val bounds = backend.targets().map { Rectangle(it.originX, it.originY, it.logicalWidth, it.logicalHeight) }
            .reduce(Rectangle::union)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            val png = tool.execute(CaptureScreenshotRequest(2560), context).content
            val image = ImageIO.read(ByteArrayInputStream(png))
            if (fixtureColors.withIndex().all { (index, color) ->
                val x = fixture.x + fixture.width * (if (index % 2 == 0) 1 else 3) / 4
                val y = fixture.y + fixture.height * (if (index < 2) 1 else 3) / 4
                image.getRGB((x - bounds.x) * image.width / bounds.width,
                    (y - bounds.y) * image.height / bounds.height) == color.rgb
            }) return
            Thread.sleep(200)
        }
        error("Screenshot tool did not capture the interactive fixture; possible Session 0 capture")
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
