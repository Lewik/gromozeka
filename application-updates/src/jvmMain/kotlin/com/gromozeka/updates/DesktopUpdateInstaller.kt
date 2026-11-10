package com.gromozeka.updates

import com.gromozeka.domain.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

class DesktopUpdateInstaller(
    private val component: UpdateComponent,
    private val installationHome: File?,
    private val prepareUpdate: suspend () -> Boolean = { true },
    private val resumeWork: suspend () -> Unit = {},
    private val onLaunched: suspend () -> Unit = {},
) : ApplicationUpdateInstaller {
    private val mac = System.getProperty("os.name").startsWith("Mac")
    private val windows = System.getProperty("os.name").startsWith("Windows")
    override val unsupportedReason: String? = when {
        System.getenv("GROMOZEKA_DEV_SLOT") != null || System.getenv("GROMOZEKA_MODE") in setOf("dev", "test", "e2e") -> "Development installations are updated from source"
        System.getProperty("os.arch") !in setOf("aarch64", "arm64", "amd64", "x86_64") -> "No update package is published for this architecture"
        mac && System.getProperty("os.arch") !in setOf("aarch64", "arm64") -> "No update package is published for Intel macOS"
        windows && System.getProperty("os.arch") !in setOf("amd64", "x86_64") -> "No update package is published for Windows ARM"
        !mac && !windows -> "This installation is managed by its deployment system"
        installationHome == null || !installationHome.isDirectory -> "Run an installed application to enable updates"
        component == UpdateComponent.CLIENT && !java.nio.file.Files.isWritable(installationHome.parentFile.toPath()) -> "The application folder is not writable by this user"
        component == UpdateComponent.WORKER && windows && !File(installationHome, ".gromozeka-worker-installation").isFile -> "Install the Worker Windows service first"
        component == UpdateComponent.WORKER && mac && !File(System.getProperty("user.home"), "Library/LaunchAgents/com.gromozeka.worker.plist").isFile -> "Install the Worker LaunchAgent first"
        else -> null
    }

    override suspend fun prepare(): Boolean = unsupportedReason == null && prepareUpdate()
    override suspend fun resume() = resumeWork()

    override suspend fun installationStopped(update: ApplicationUpdateRequest, store: UpdateLocalStore): Boolean = withContext(Dispatchers.IO) {
        val handoff = File(store.directory, "handoff-${update.id}")
        val started = handoff.takeIf(File::isFile)?.readText()?.toLongOrNull() ?: return@withContext true
        if (System.currentTimeMillis() - started < 120_000) return@withContext false
        val pid = File(store.directory, "installer-${update.id}.pid").takeIf(File::isFile)?.readText()?.trim()?.toLongOrNull()
            ?: return@withContext true
        !ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
    }

    override suspend fun confirmed(update: ApplicationUpdateRequest) = withContext(Dispatchers.IO) {
        val home = installationHome?.canonicalFile ?: return@withContext
        val previous = File("${home.path}.previous-${update.id}")
        if (previous.isDirectory && !java.nio.file.Files.isSymbolicLink(previous.toPath())) previous.deleteRecursively()
    }

    override suspend fun install(file: File, update: ApplicationUpdateRequest, store: UpdateLocalStore) = withContext(Dispatchers.IO) {
        check(unsupportedReason == null)
        require(Regex("[a-zA-Z0-9-]{1,80}").matches(update.id))
        val home = requireNotNull(installationHome).canonicalFile
        val extension = if (mac) "sh" else "ps1"
        val script = File(store.directory, "install-${update.id}.$extension")
        javaClass.getResourceAsStream("/updates/install.$extension")!!.use { input -> script.outputStream().use(input::copyTo) }
        val arguments = listOf(file.canonicalPath, home.path, component.name, update.id,
            ProcessHandle.current().pid().toString(), File(store.directory, "result.json").path)
        File(store.directory, "result.json").delete()
        val log = File(store.directory, "installer.log")
        if (mac) {
            val label = "com.gromozeka.update.${update.id}"
            val plist = File(store.directory, "installer-${update.id}.plist")
            fun xml(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
            val uid = ProcessBuilder("/usr/bin/id", "-u").start().let { process ->
                val value = process.inputStream.bufferedReader().readText().trim()
                check(process.waitFor() == 0 && value.all(Char::isDigit)); value
            }
            val args = listOf("/bin/bash", script.path) + arguments + listOf(label, uid)
            plist.writeText("""<?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                <plist version="1.0"><dict><key>Label</key><string>$label</string>
                <key>ProgramArguments</key><array>${args.joinToString("") { "<string>${xml(it)}</string>" }}</array>
                <key>RunAtLoad</key><true/><key>StandardOutPath</key><string>${xml(log.path)}</string>
                <key>StandardErrorPath</key><string>${xml(log.path)}</string></dict></plist>""".trimIndent())
            val process = ProcessBuilder("/bin/launchctl", "bootstrap", "gui/$uid", plist.path).redirectErrorStream(true).redirectOutput(log).start()
            check(process.waitFor(15, TimeUnit.SECONDS) && process.exitValue() == 0) { "Could not start independent update job" }
        } else {
            ProcessBuilder(listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", script.path) + arguments)
                .directory(store.directory).redirectErrorStream(true).redirectOutput(log).start()
        }
        onLaunched()
    }

    companion object {
        fun platform(): UpdatePlatform = when {
            System.getProperty("os.name").startsWith("Mac") -> UpdatePlatform.MACOS_ARM64
            System.getProperty("os.name").startsWith("Windows") -> UpdatePlatform.WINDOWS_X64
            else -> UpdatePlatform.LINUX_X64
        }

        fun clientHome(): File? {
            val command = ProcessHandle.current().info().command().orElse(null) ?: return null
            val executable = File(command)
            if (executable.name.equals("Gromozeka.exe", true)) return executable.parentFile
            return generateSequence(executable.parentFile) { it.parentFile }.firstOrNull { it.name == "Gromozeka.app" }
        }
    }
}
