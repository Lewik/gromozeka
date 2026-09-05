package com.gromozeka.server

import com.gromozeka.domain.model.AgentDefinition
import com.gromozeka.domain.model.AiProvider
import com.gromozeka.domain.model.Conversation.Message.ContentItem.ToolCall
import com.gromozeka.domain.model.ai.*
import com.gromozeka.domain.service.ConversationRuntimeTaskTarget
import com.gromozeka.domain.service.ConversationRuntimeWorkerId
import com.gromozeka.domain.service.WorkerRequestDelivery
import com.gromozeka.remote.protocol.*
import io.ktor.http.ContentType
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.net.InetAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

@EnabledIfEnvironmentVariable(named = "GROMOZEKA_ANDROID_LIFECYCLE_TEST", matches = "true")
class AndroidWorkerLifecycleTest {
    private val serial = requiredEnvironment("ANDROID_LIFECYCLE_SERIAL")
    private val adb = Path.of(requiredEnvironment("ANDROID_HOME"), "platform-tools", "adb").toString()
    private val apk = requiredEnvironment("ANDROID_LIFECYCLE_APK")
    private val testApk = requiredEnvironment("ANDROID_LIFECYCLE_TEST_APK")

    @Test
    fun `real Android Worker survives network loss sleep update and reboot and respects disable`() = runBlocking {
        require(serial.startsWith("emulator-")) { "Only a disposable emulator is allowed" }
        assertEquals("1", shell("getprop", "ro.kernel.qemu").trim())
        assertTrue(shell("getprop", "ro.build.version.sdk").trim().toInt() >= 35)
        val aapt = Files.list(Path.of(requiredEnvironment("ANDROID_HOME"), "build-tools")).use { versions ->
            versions.sorted(Comparator.reverseOrder()).map { it.resolve("aapt2") }
                .filter(Files::isRegularFile).findFirst().orElseThrow()
        }
        assertEquals(PACKAGE, runCommand(listOf(aapt.toString(), "dump", "packagename", apk)).trim())
        assertEquals("$PACKAGE.test", runCommand(listOf(aapt.toString(), "dump", "packagename", testApk)).trim())
        Fixture().use { fixture ->
            try {
                mark("install isolated lifecycle application")
                command("install", "-r", apk)
                command("install", "-r", testApk)
                assertTrue(shell("pm", "clear", PACKAGE).contains("Success"))
                val setup = shell("am", "instrument", "-w", "-e", "lifecycleSetup", "true", RUNNER)
                assertTrue(setup.contains("Lifecycle fixture enrollment prepared."), setup)
                launch()
                fixture.awaitConnectionAfter(0)
                fixture.status("initial")

                mark("home and screen off")
                shell("input", "keyevent", "KEYCODE_HOME")
                shell("input", "keyevent", "KEYCODE_SLEEP")
                fixture.status("screen-off")
                fixture.sound("screen-off-sound")

                mark("offline delivery and TTL")
                shell("svc", "wifi", "disable")
                shell("svc", "data", "disable")
                delay(3.seconds)
                val expired = fixture.enqueue("expired-offline", ttlSeconds = 2, sendNow = false)
                val pending = fixture.enqueue("pending-offline", ttlSeconds = 150, sendNow = false)
                delay(3.seconds)
                shell("svc", "data", "enable")
                fixture.expectSuccess(pending)
                assertEquals("EXPIRED", withTimeout(120.seconds) { expired.await() }.errorCode)
                mark("cellular to Wi-Fi handover")
                shell("svc", "wifi", "enable")
                delay(5.seconds)
                fixture.status("wifi-restored")

                mark("package update without opening the application")
                var before = fixture.connections.get()
                command("install", "-r", apk)
                fixture.awaitConnectionAfter(before)
                fixture.status("package-replaced")

                mark("reboot without opening the application")
                before = fixture.connections.get()
                reboot()
                fixture.awaitConnectionAfter(before)
                fixture.status("after-boot")
                fixture.sound("sound-after-boot")

                mark("ordinary Doze and wake recovery")
                shell("cmd", "deviceidle", "whitelist", "-$PACKAGE")
                shell("cmd", "deviceidle", "tempwhitelist", "-r", PACKAGE)
                shell("dumpsys", "battery", "unplug")
                shell("input", "keyevent", "KEYCODE_SLEEP")
                assertTrue(shell("dumpsys", "deviceidle", "force-idle").contains("deep idle"))
                assertEquals("IDLE", shell("cmd", "deviceidle", "get", "deep").trim())
                val uid = requireNotNull(Regex("(?:appId|userId)=(\\d+)").find(shell("dumpsys", "package", PACKAGE))).groupValues[1]
                mark(shell("dumpsys", "netpolicy").lineSequence().first { it.trimStart().startsWith("UID=$uid state=") }.trim())
                val sleeping = fixture.enqueue("ordinary-doze", ttlSeconds = 150)
                delay(5.seconds)
                mark("ordinary Doze response arrived while idle: ${sleeping.isCompleted}")
                shell("dumpsys", "deviceidle", "unforce")
                shell("input", "keyevent", "KEYCODE_WAKEUP")
                fixture.expectSuccess(sleeping)

                mark("explicit battery exemption permits commands during Doze")
                shell("cmd", "deviceidle", "whitelist", "+$PACKAGE")
                shell("input", "keyevent", "KEYCODE_SLEEP")
                assertTrue(shell("dumpsys", "deviceidle", "force-idle").contains("deep idle"))
                assertEquals("IDLE", shell("cmd", "deviceidle", "get", "deep").trim())
                fixture.status("exempt-doze")
                fixture.sound("sound-in-exempt-doze")
                shell("dumpsys", "deviceidle", "unforce")
                shell("dumpsys", "battery", "reset")
                shell("input", "keyevent", "KEYCODE_WAKEUP")

                mark("force-stop remains stopped until manual launch")
                shell("am", "force-stop", PACKAGE)
                before = fixture.connections.get()
                delay(8.seconds)
                assertEquals(before, fixture.connections.get())
                launch()
                fixture.awaitConnectionAfter(before)
                fixture.status("manual-recovery")

                mark("user disable survives reboot")
                shell("wm", "dismiss-keyguard")
                tapText("Disable remote commands")
                eventually { !shell("dumpsys", "activity", "services", PACKAGE).contains("isForeground=true") }
                before = fixture.connections.get()
                reboot()
                delay(20.seconds)
                assertEquals(before, fixture.connections.get(), "Disabled Gateway reconnected after reboot")
                assertFalse(shell("dumpsys", "activity", "services", PACKAGE).contains("isForeground=true"))
                mark("PASS")
            } finally {
                runCatching { shell("dumpsys", "deviceidle", "unforce") }
                runCatching { shell("dumpsys", "battery", "reset") }
                runCatching { shell("cmd", "deviceidle", "whitelist", "-$PACKAGE") }
                runCatching { shell("svc", "wifi", "enable") }
                runCatching { shell("svc", "data", "enable") }
                runCatching { shell("input", "keyevent", "KEYCODE_WAKEUP") }
                runCatching { shell("am", "force-stop", PACKAGE) }
            }
        }
    }

    private fun launch() {
        shell("input", "keyevent", "KEYCODE_WAKEUP")
        shell("wm", "dismiss-keyguard")
        shell("am", "start", "-W", "-n", "$PACKAGE/com.gromozeka.mobile.worker.MainActivity")
    }

    private suspend fun reboot() {
        command("reboot")
        command("wait-for-device")
        eventually(120) { shell("getprop", "sys.boot_completed").trim() == "1" }
        shell("input", "keyevent", "KEYCODE_WAKEUP")
        shell("wm", "dismiss-keyguard")
    }

    private suspend fun tapText(text: String) {
        eventually {
            shell("uiautomator", "dump", "/data/local/tmp/worker-lifecycle-ui.xml")
            val xml = shell("cat", "/data/local/tmp/worker-lifecycle-ui.xml")
            val bounds = Regex("text=\"${Regex.escape(text)}\"[^>]*bounds=\"\\[(\\d+),(\\d+)]\\[(\\d+),(\\d+)]\"")
                .find(xml)?.groupValues
            if (bounds == null) false else {
                val (left, top, right, bottom) = bounds.drop(1).map(String::toInt)
                shell("input", "tap", ((left + right) / 2).toString(), ((top + bottom) / 2).toString())
                true
            }
        }
    }

    private fun shell(vararg arguments: String) = command("shell", *arguments)

    private fun command(vararg arguments: String): String = runCommand(listOf(adb, "-s", serial, *arguments))

    private fun runCommand(arguments: List<String>): String {
        val output = Files.createTempFile("worker-lifecycle-adb-", ".log")
        try {
            val process = ProcessBuilder(arguments).redirectErrorStream(true)
                .redirectOutput(output.toFile()).start()
            if (!process.waitFor(90, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                error("Command timed out: $arguments")
            }
            return Files.readString(output).also { check(process.exitValue() == 0) { "Command $arguments: $it" } }
        } finally { Files.deleteIfExists(output) }
    }

    private class Fixture : AutoCloseable {
        val connections = AtomicInteger()
        private val requests = ConcurrentHashMap<String, WorkerGatewayMessage.Request>()
        private val responses = ConcurrentHashMap<String, CompletableDeferred<WorkerGatewayMessage.Response>>()
        @Volatile private var current: DefaultWebSocketSession? = null
        private val server = embeddedServer(CIO, host = "127.0.0.1", port = 18877) {
            install(WebSockets)
            routing {
                webSocket("/worker/ws") {
                    check(call.request.headers["Authorization"] == "Bearer $CREDENTIAL")
                    val hello = WorkerGatewayCodec.decode((incoming.receive() as Frame.Binary).readBytes()) as WorkerGatewayMessage.Hello
                    check(hello.registration.identity.workerId.value == "android-lifecycle")
                    send(Frame.Binary(true, WorkerGatewayCodec.encode(WorkerGatewayMessage.Welcome(5, catalog(), emptyList()))))
                    try {
                        for (frame in incoming) {
                            if (frame !is Frame.Binary) continue
                            when (val message = WorkerGatewayCodec.decode(frame.readBytes())) {
                                is WorkerGatewayMessage.Ready -> {
                                    check(message.tools.map { it.definition.name }.containsAll(listOf("grz_get_device_status", "grz_play_loud_sound")))
                                    current = this
                                    connections.incrementAndGet()
                                    requests.values.forEach { send(Frame.Binary(true, WorkerGatewayCodec.encode(it))) }
                                }
                                is WorkerGatewayMessage.Response -> {
                                    requests.remove(message.requestId)
                                    responses[message.requestId]?.complete(message)
                                    send(Frame.Binary(true, WorkerGatewayCodec.encode(WorkerGatewayMessage.ResponseAcknowledged(message.requestId))))
                                }
                                else -> Unit
                            }
                        }
                    } finally { if (current === this) current = null }
                }
                post("/api/worker/events") {
                    check(call.request.headers["Authorization"] == "Bearer $CREDENTIAL")
                    val batch = Json.decodeFromString<WorkerEventBatchRequest>(call.receiveText())
                    call.respondText(Json.encodeToString(WorkerEventBatchResponse(batch.events.map { it.id }.toSet(), emptySet(), Clock.System.now())), ContentType.Application.Json)
                }
                post("/api/worker/heartbeat") {
                    check(call.request.headers["Authorization"] == "Bearer $CREDENTIAL")
                    call.respondText(Json.encodeToString(WorkerHeartbeatResponse(Clock.System.now())), ContentType.Application.Json)
                }
            }
        }.start(wait = false)
        private val tls = try {
            TlsTunnel(Path.of(requiredEnvironment("ANDROID_LIFECYCLE_TLS_STORE")))
        } catch (error: Exception) {
            server.stop(0, 1_000)
            throw error
        }

        suspend fun awaitConnectionAfter(previous: Int) = eventually(120) { connections.get() > previous && current != null }

        suspend fun enqueue(id: String, ttlSeconds: Int = 150, sendNow: Boolean = true, sound: Boolean = false): CompletableDeferred<WorkerGatewayMessage.Response> {
            val deferred = CompletableDeferred<WorkerGatewayMessage.Response>()
            responses[id] = deferred
            val request = WorkerGatewayMessage.Request(id, WorkerGatewayOperation.TOOL_EXECUTION,
                Json.encodeToString(WorkerToolExecutionRequest(
                    ConversationRuntimeTaskTarget.Worker(ConversationRuntimeWorkerId("android-lifecycle")),
                    listOf(ToolCall(ToolCall.Id(id), ToolCall.Data(if (sound) "grz_play_loud_sound" else "grz_get_device_status",
                        if (sound) JsonObject(mapOf("duration_seconds" to JsonPrimitive(2))) else JsonObject(emptyMap())))), emptyMap(),
                )).encodeToByteArray(), WorkerRequestDelivery(Clock.System.now() + ttlSeconds.seconds, 10_000))
            requests[id] = request
            if (sendNow) current?.send(Frame.Binary(true, WorkerGatewayCodec.encode(request)))
            return deferred
        }

        suspend fun status(id: String) = expectSuccess(enqueue(id))
        suspend fun sound(id: String) = expectSuccess(enqueue(id, sound = true))

        suspend fun expectSuccess(response: CompletableDeferred<WorkerGatewayMessage.Response>) {
            val result = withTimeout(120.seconds) { response.await() }
            assertEquals(WorkerGatewayMessage.Response.Status.SUCCEEDED, result.status, result.toString())
            val tools = Json.decodeFromString<WorkerToolExecutionResponse>(requireNotNull(result.payload).decodeToString())
            assertEquals(1, tools.results.size)
            assertFalse(tools.results.single().isError, tools.toString())
        }

        override fun close() {
            tls.close()
            server.stop(100, 1_000)
        }
    }

    private class TlsTunnel(store: Path) : AutoCloseable {
        private val threads = Executors.newVirtualThreadPerTaskExecutor()
        private val sockets = ConcurrentHashMap.newKeySet<Socket>()
        private val listener: SSLServerSocket
        init {
            val password = "lifecycle-test".toCharArray()
            val keys = KeyStore.getInstance("PKCS12").apply { Files.newInputStream(store).use { load(it, password) } }
            val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, password) }
            val context = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
            listener = context.serverSocketFactory.createServerSocket(18876, 50, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
            threads.submit {
                while (!listener.isClosed) {
                    val secure = try { listener.accept() } catch (_: Exception) { break }
                    sockets += secure
                    threads.submit {
                        secure.use {
                            Socket("127.0.0.1", 18877).use { plain ->
                                sockets += plain
                                try {
                                    threads.submit {
                                        runCatching { plain.getInputStream().copyTo(secure.getOutputStream()) }
                                        runCatching { secure.close() }
                                    }
                                    runCatching { secure.getInputStream().copyTo(plain.getOutputStream()) }
                                } finally { sockets -= plain; sockets -= secure }
                            }
                        }
                    }
                }
            }
        }

        override fun close() {
            listener.close()
            sockets.forEach { runCatching { it.close() } }
            threads.shutdownNow()
        }
    }

    companion object {
        private const val PACKAGE = "com.gromozeka.mobile.worker.lifecycle"
        private const val RUNNER = "$PACKAGE.test/com.gromozeka.mobile.worker.GatewaySmokeInstrumentation"
        private const val CREDENTIAL = "android-lifecycle-fixture-credential"
        private fun requiredEnvironment(name: String): String = requireNotNull(System.getenv(name)) { "$name must be explicitly set" }
        private fun mark(stage: String) { println("${Clock.System.now()} Android lifecycle: $stage") }
        private suspend fun eventually(seconds: Int = 30, condition: suspend () -> Boolean) {
            withTimeout(seconds.seconds) { while (!condition()) delay(500) }
        }
        private fun catalog(): AiCatalogSnapshot {
            val connection = AiConnection.OpenAiApi(AiConnection.Id("connection"), "Test", true)
            val configuration = AiModelConfiguration(AiModelConfiguration.Id("model"), connection.id, "test", "Test")
            val spec = AiModelSpec("test", AiProvider.OPENAI, AiModelCapability.entries.toSet(),
                limits = AiModelSpec.Limits(textGeneration = AiModelSpec.Limits.TextGeneration(contextWindowTokens = 1024),
                    embeddings = AiModelSpec.Limits.Embeddings(dimensions = 8)))
            return AiCatalogSnapshot(AiCatalog(listOf(connection), listOf(spec), listOf(configuration),
                AiRuntimeAssignment.Purpose.entries.filter { it.requiresExplicitAssignment }.map { AiRuntimeAssignment(it, AiRuntimeSelection(configuration.id)) },
                AgentDefinition.Id("agent")), 1)
        }
    }
}
