package com.gromozeka.infrastructure.ai.tool

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.security.MessageDigest

/** Two shared files shipped in the Worker. No interpreter install, credentials or global environment edits. */
internal object LocalScriptSdk {
    private val resources: Map<String, ByteArray> by lazy {
        listOf("grz.py", "grz.sh").associateWith { name ->
            checkNotNull(javaClass.getResourceAsStream("/script-sdk/$name")) { "Bundled script SDK is missing" }
                .use { it.readBytes() }
        }
    }
    private val version: String by lazy {
        val digest = MessageDigest.getInstance("SHA-256")
        resources.forEach { (name, bytes) ->
            digest.update(name.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            digest.update(bytes)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Synchronized
    fun materialize(home: File): File {
        val directory = File(home, "script-sdk/$version").toPath()
        Files.createDirectories(directory)
        check(Files.isDirectory(directory, NOFOLLOW_LINKS)) { "Invalid shared script SDK directory" }
        resources.forEach { (name, bytes) ->
            val destination = directory.resolve(name)
            if (!Files.exists(destination, NOFOLLOW_LINKS)) {
                val temporary = Files.createTempFile(directory, ".$name-", ".tmp")
                try {
                    Files.write(temporary, bytes)
                    Files.move(temporary, destination, ATOMIC_MOVE)
                } finally {
                    Files.deleteIfExists(temporary)
                }
            }
            // Do not silently execute a changed/shared script or overwrite an unexpected file.
            check(Files.isRegularFile(destination, NOFOLLOW_LINKS) && Files.readAllBytes(destination).contentEquals(bytes)) {
                "Shared script SDK file does not match this Worker"
            }
        }
        return directory.toFile()
    }

    fun environment(home: File, supplied: Map<String, String>, inherited: Map<String, String> = System.getenv()): Map<String, String> {
        val directory = materialize(home).absolutePath
        val previousPythonPath = supplied["PYTHONPATH"] ?: inherited["PYTHONPATH"]
        return supplied + mapOf(
            "GRZ_SDK_HOME" to directory,
            "PYTHONPATH" to listOfNotNull(directory, previousPythonPath?.takeIf(String::isNotEmpty)).joinToString(File.pathSeparator),
            // A monitor/filter without a Conversation must not inherit another launch's routing.
            "GRZ_MCP_CONTEXT" to (supplied["GRZ_MCP_CONTEXT"] ?: "{}"),
        )
    }
}
