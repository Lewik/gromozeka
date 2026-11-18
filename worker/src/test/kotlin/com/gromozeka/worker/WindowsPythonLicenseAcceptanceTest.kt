package com.gromozeka.worker

import com.gromozeka.infrastructure.ai.tool.BundledPythonRuntime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WindowsPythonLicenseAcceptanceTest {
    private val terms = "Synthetic fixture conditions.\nOnly test data, not a legal agreement."

    @Test
    fun `interactive agreement is persisted and restart does not ask or rewrite`() = fixture { root, license ->
        val home = root.resolve("home")
        val first = acceptance(home, license)
        var prompts = 0
        first.requireAcceptance(confirm = { message ->
            prompts++
            assertContains(message, terms)
            assertContains(message, license.toAbsolutePath().toUri().toString())
            " YES "
        })
        val before = Files.getLastModifiedTime(first.receiptPath)
        val content = Files.readString(first.receiptPath)
        val receipt = Json.parseToJsonElement(content).jsonObject
        Instant.parse(receipt.getValue("acceptedAt").jsonPrimitive.content)
        acceptance(home, license).requireAcceptance(confirm = { error("Must not ask twice") })
        assertEquals(1, prompts)
        assertEquals(before, Files.getLastModifiedTime(first.receiptPath))
        assertEquals(content, Files.readString(first.receiptPath))
    }

    @Test
    fun `decline blank input and EOF do not record agreement or continue startup`() = fixture { root, license ->
        listOf("no", "", " ", null).forEachIndexed { index, answer ->
            val home = root.resolve("declined-$index")
            var continued = false
            assertFailsWith<IllegalStateException> {
                acceptance(home, license).requireAcceptance(confirm = { answer })
                continued = true
            }
            assertFalse(continued)
            assertFalse(Files.exists(home))
        }
    }

    @Test
    fun `unattended startup never implies agreement and explicit flag needs no input`() = fixture { root, license ->
        val home = root.resolve("service")
        val gate = acceptance(home, license)
        val error = assertFailsWith<IllegalStateException> { gate.requireAcceptance() }
        assertContains(error.message.orEmpty(), "accept-licenses --yes")
        assertFalse(Files.exists(home))
        gate.requireAcceptance(explicitlyAccepted = true, confirm = { error("Must not read stdin") })
        assertTrue(Files.isRegularFile(gate.receiptPath))
        acceptance(home, license).requireAcceptance()
    }

    @Test
    fun `Python version and line ending changes do not ask again`() = fixture { root, license ->
        val home = root.resolve("home")
        val original = acceptance(home, license)
        original.requireAcceptance(explicitlyAccepted = true)
        Files.writeString(license, "New Python version and notices\r\n" + terms.replace("\n", "\r\n\r\n"))
        val updated = WindowsPythonLicenseAcceptance(home, license, terms.replace("\n", "\r\n"))
        assertEquals(original.receiptPath, updated.receiptPath)
        updated.requireAcceptance(confirm = { error("Unchanged conditions must not ask again") })
    }

    @Test
    fun `changed conditions require agreement without invalidating a rollback`() = fixture { root, license ->
        val home = root.resolve("home")
        val old = acceptance(home, license)
        old.requireAcceptance(explicitlyAccepted = true)
        val oldReceipt = Files.readString(old.receiptPath)
        val changedTerms = "$terms Additional synthetic condition."
        Files.writeString(license, changedTerms)
        val changed = WindowsPythonLicenseAcceptance(home, license, changedTerms)
        assertNotEquals(old.receiptPath, changed.receiptPath)
        assertFailsWith<IllegalStateException> { changed.requireAcceptance() }
        changed.requireAcceptance(explicitlyAccepted = true)
        assertEquals(oldReceipt, Files.readString(old.receiptPath))
        Files.writeString(license, terms)
        old.requireAcceptance()
        assertTrue(Files.isRegularFile(changed.receiptPath))
    }

    @Test
    fun `service setup carries only the matching existing agreement into its own home`() = fixture { root, license ->
        val user = acceptance(root.resolve("user"), license)
        user.requireAcceptance(explicitlyAccepted = true)
        Files.writeString(user.receiptPath.parent.resolve("unrelated.json"), "not a receipt")
        val service = acceptance(root.resolve("service"), license)
        service.requireAcceptance(previousAcceptanceDirectory = user.receiptPath.parent, confirm = { error("Already accepted") })
        assertEquals(Files.readString(user.receiptPath), Files.readString(service.receiptPath))
        assertFalse(Files.exists(service.receiptPath.parent.resolve("unrelated.json")))
        acceptance(root.resolve("service"), license).requireAcceptance()
    }

    @Test
    fun `previous agreement for different conditions cannot authorize new ones`() = fixture { root, license ->
        val user = acceptance(root.resolve("user"), license)
        user.requireAcceptance(explicitlyAccepted = true)
        val changedTerms = "$terms Changed."
        Files.writeString(license, changedTerms)
        val serviceHome = root.resolve("service")
        val service = WindowsPythonLicenseAcceptance(serviceHome, license, changedTerms)
        assertFailsWith<IllegalStateException> {
            service.requireAcceptance(previousAcceptanceDirectory = user.receiptPath.parent)
        }
        assertFalse(Files.exists(serviceHome))
    }

    @Test
    fun `corrupt receipts are errors not implicit agreement or silent repair`() = fixture { root, license ->
        val gate = acceptance(root.resolve("home"), license)
        Files.createDirectories(gate.receiptPath.parent)
        for (broken in listOf("not json", "{}", """{"format":1,"termsSha256":"wrong","acceptedAt":"2026-01-01T00:00:00Z"}""")) {
            Files.writeString(gate.receiptPath, broken)
            assertFails { gate.requireAcceptance(explicitlyAccepted = true) }
            assertEquals(broken, Files.readString(gate.receiptPath))
        }
    }

    @Test
    fun `missing or different bundled conditions cannot be accepted`() = fixture { root, license ->
        val home = root.resolve("home")
        Files.writeString(license, "different license")
        assertFailsWith<IllegalStateException> { acceptance(home, license).requireAcceptance(explicitlyAccepted = true) }
        Files.delete(license)
        assertFailsWith<IllegalStateException> { acceptance(home, license).requireAcceptance(explicitlyAccepted = true) }
        assertFalse(Files.exists(home))
    }

    @Test
    fun `license command options are explicit and unknown options fail`() {
        assertEquals(WorkerLicenseOptions(false, null), WorkerLicenseOptions.parse(emptyList()))
        assertEquals(WorkerLicenseOptions(true, Path.of("prior agreements")),
            WorkerLicenseOptions.parse(listOf("--from", "prior agreements", "--yes")))
        for (arguments in listOf(listOf("--yes", "--yes"), listOf("--from"),
            listOf("--from", "one", "--from", "two"), listOf("--accept"), listOf("--yes=false"))) {
            assertFails { WorkerLicenseOptions.parse(arguments) }
        }
    }

    @Test
    fun `native Windows bundle contains the exact conditions shown to the user`() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val license = Files.readString(BundledPythonRuntime.executable().toPath().parent.resolve("LICENSE.txt"))
        val whitespace = Regex("\\s+")
        assertTrue(license.trim().replace(whitespace, " ").contains(bundledWindowsPythonConditions().trim().replace(whitespace, " ")))
    }

    @Test
    fun `native Windows bootstrap is refused before consuming an open stdin pipe`() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        refusedMain(listOf("configure", "--server", "http://127.0.0.1:1"))
    }

    @Test
    fun `native Windows enrollment is refused before sending its token`() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                requests.incrementAndGet()
                exchange.sendResponseHeaders(503, -1)
                exchange.close()
            }
            start()
        }
        try {
            refusedMain(listOf("enroll", "--server", "http://127.0.0.1:${server.address.port}",
                "--worker-id", "license-fixture", "--token", "synthetic-enrollment-token-never-sent"))
            assertEquals(0, requests.get())
        } finally { server.stop(0) }
    }

    private fun refusedMain(arguments: List<String>) {
        val root = Files.createTempDirectory("grz-license-main-")
        val home = root.resolve("home")
        val config = root.resolve("worker.yaml")
        val output = root.resolve("process.log")
        val command = listOf(
            Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
            "-Dgromozeka.project.root=${requireNotNull(System.getProperty("gromozeka.project.root"))}",
            "-cp", System.getProperty("java.class.path"), "com.gromozeka.worker.GromozekaWorkerMainKt",
        ) + arguments + listOf("--config", config.toString())
        val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).apply {
            environment()["GROMOZEKA_HOME"] = home.toString()
        }.start()
        try {
            // Stdin deliberately remains open: a premature bootstrap read would block here.
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Worker blocked instead of refusing missing consent")
            assertEquals(2, process.exitValue(), Files.readString(output))
            assertContains(Files.readString(output), "Windows Python conditions have not been accepted")
            assertFalse(Files.exists(config))
            assertFalse(Files.exists(home))
        } finally {
            if (process.isAlive) process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
            process.outputStream.close()
            assertTrue(root.toFile().deleteRecursively())
        }
    }

    private fun acceptance(home: Path, license: Path) = WindowsPythonLicenseAcceptance(home, license, terms)

    private fun fixture(block: (Path, Path) -> Unit) {
        val root = Files.createTempDirectory("grz-license-fixture-")
        val license = root.resolve("LICENSE.txt")
        Files.writeString(license, "Synthetic Python build\n$terms\nOther synthetic notices")
        try { block(root, license) } finally { assertTrue(root.toFile().deleteRecursively()) }
    }
}
