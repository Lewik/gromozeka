package com.gromozeka.worker

import com.gromozeka.infrastructure.ai.tool.BundledPythonRuntime
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.NoSuchFileException
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.time.Instant

internal const val WINDOWS_PYTHON_ACCEPTANCE_DIRECTORY = "licenses/windows-python"

/** A local acknowledgement, not an authorization grant or a security boundary. */
internal class WindowsPythonLicenseAcceptance(
    home: Path,
    private val pythonLicense: Path,
    private val conditions: String = bundledWindowsPythonConditions(),
) {
    private val normalizedConditions = normalize(conditions).also { require(it.isNotEmpty()) }
    private val digest = MessageDigest.getInstance("SHA-256")
        .digest(normalizedConditions.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    val receiptPath: Path = home.resolve(WINDOWS_PYTHON_ACCEPTANCE_DIRECTORY).resolve("$digest.json")

    fun requireAcceptance(
        explicitlyAccepted: Boolean = false,
        previousAcceptanceDirectory: Path? = null,
        confirm: ((String) -> String?)? = null,
    ) {
        check(Files.isRegularFile(pythonLicense) && normalize(Files.readString(pythonLicense)).contains(normalizedConditions)) {
            "Bundled Windows Python does not contain the expected redistribution conditions: $pythonLicense"
        }
        if (readReceipt(receiptPath) != null) return
        previousAcceptanceDirectory?.let { directory ->
            require(Files.isDirectory(directory)) { "License acceptance source is not a directory: $directory" }
            readReceipt(directory.resolve("$digest.json"))?.let { previous ->
                persist(previous)
                return
            }
        }
        check(explicitlyAccepted || confirm != null) {
            "Windows Python conditions have not been accepted. Review $pythonLicense, then run " +
                "'gromozeka-worker.cmd accept-licenses' in a terminal, or use 'accept-licenses --yes' " +
                "after reviewing the terms for unattended setup. Use the same GROMOZEKA_HOME."
        }
        val accepted = explicitlyAccepted || confirm!!.invoke(
            "This Windows Worker includes Python and Microsoft runtime components.\n" +
                "Complete Python licenses: ${pythonLicense.toAbsolutePath().toUri()}\n" +
                "Microsoft terms: https://visualstudio.microsoft.com/license-terms/vs2022-ga-community/\n\n" +
                conditions.trim() + "\n\nAccept these Windows Python conditions? [yes/NO]: "
        )?.trim().equals("yes", ignoreCase = true)
        check(accepted) { "Windows Python conditions were not accepted; Worker startup/configuration was cancelled." }
        persist(Receipt(format = 1, termsSha256 = digest, acceptedAt = Instant.now().toString()))
    }

    private fun readReceipt(path: Path): Receipt? {
        val attributes = try {
            Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        } catch (_: NoSuchFileException) {
            return null
        }
        check(attributes.isRegularFile) { "License acceptance receipt is not a regular file: $path" }
        val receipt = Json.decodeFromString<Receipt>(Files.readString(path))
        check(receipt.format == 1 && receipt.termsSha256 == digest) {
            "Invalid license acceptance receipt: $path"
        }
        Instant.parse(receipt.acceptedAt)
        return receipt
    }

    private fun persist(receipt: Receipt) {
        Files.createDirectories(receiptPath.parent)
        val temporary = Files.createTempFile(receiptPath.parent, ".acceptance-", ".tmp")
        try {
            Files.writeString(temporary, Json.encodeToString(receipt) + "\n")
            Files.move(temporary, receiptPath, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    @Serializable
    private data class Receipt(val format: Int, val termsSha256: String, val acceptedAt: String)

    companion object {
        fun current(): WindowsPythonLicenseAcceptance {
            check(System.getProperty("os.name").startsWith("Windows")) { "These additional conditions apply only to Windows Workers" }
            val home = System.getProperty("GROMOZEKA_HOME") ?: System.getenv("GROMOZEKA_HOME")
                ?: Path.of(System.getProperty("user.home"), ".gromozeka").toString()
            require(home.isNotBlank()) { "GROMOZEKA_HOME must not be blank" }
            val pythonLicense = BundledPythonRuntime.executable().toPath().parent.resolve("LICENSE.txt")
            return WindowsPythonLicenseAcceptance(Path.of(home), pythonLicense)
        }

        private fun normalize(text: String) = text.trim().replace(Regex("\\s+"), " ")
    }
}

// CPython v3.14.10/PC/crtlicense.txt; kept separately so Python version/copyright updates do not ask again.
internal fun bundledWindowsPythonConditions(): String = checkNotNull(
    WindowsPythonLicenseAcceptance::class.java.getResourceAsStream("/licenses/windows-python-conditions.txt")
) { "Windows Python conditions are missing from the Worker" }.bufferedReader(Charsets.UTF_8).use { it.readText() }

internal data class WorkerLicenseOptions(val accepted: Boolean, val previousAcceptanceDirectory: Path?) {
    companion object {
        fun parse(arguments: List<String>): WorkerLicenseOptions {
            var accepted = false
            var previous: Path? = null
            var index = 0
            while (index < arguments.size) {
                when (val option = arguments[index++]) {
                    "--yes" -> {
                        require(!accepted) { "Duplicate --yes" }
                        accepted = true
                    }
                    "--from" -> {
                        require(previous == null && index < arguments.size) { "--from requires one acceptance directory" }
                        previous = Path.of(arguments[index++])
                    }
                    else -> error("Unknown license option: $option")
                }
            }
            return WorkerLicenseOptions(accepted, previous)
        }
    }
}

internal fun confirmWindowsPythonConditions(options: WorkerLicenseOptions = WorkerLicenseOptions(false, null), interactive: Boolean = true) {
    val console = if (interactive) System.console() else null
    WindowsPythonLicenseAcceptance.current().requireAcceptance(
        explicitlyAccepted = options.accepted,
        previousAcceptanceDirectory = options.previousAcceptanceDirectory,
        confirm = console?.let { { message -> it.readLine("%s", message) } },
    )
}
