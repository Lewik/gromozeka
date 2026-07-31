package com.gromozeka.presentation.services

import com.gromozeka.client.RemoteSessionCredentialStore
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission

class DesktopRemoteSessionCredentialStore(
    private val file: File,
) : RemoteSessionCredentialStore {
    private val json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
    }

    @Synchronized
    override fun load(serverKey: String): String? = readSessions()[serverKey]

    @Synchronized
    override fun save(serverKey: String, encodedSession: String?) {
        val sessions = readSessions().toMutableMap()
        if (encodedSession == null) {
            sessions.remove(serverKey)
        } else {
            sessions[serverKey] = encodedSession
        }
        writeSessions(sessions)
    }

    private fun readSessions(): Map<String, String> =
        if (!file.exists()) {
            emptyMap()
        } else {
            runCatching { json.decodeFromString<Map<String, String>>(file.readText()) }
                .getOrDefault(emptyMap())
        }

    private fun writeSessions(sessions: Map<String, String>) {
        val target = file.absoluteFile
        if (sessions.isEmpty()) {
            Files.deleteIfExists(target.toPath())
            return
        }
        val parent = requireNotNull(target.parentFile)
        Files.createDirectories(parent.toPath())
        setPermissions(parent, DIRECTORY_PERMISSIONS)
        val temporaryFile = Files.createTempFile(parent.toPath(), "${target.name}.", ".tmp")
        try {
            Files.writeString(temporaryFile, json.encodeToString(sessions))
            setPermissions(temporaryFile.toFile(), FILE_PERMISSIONS)
            try {
                Files.move(
                    temporaryFile,
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporaryFile, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            setPermissions(target, FILE_PERMISSIONS)
        } finally {
            Files.deleteIfExists(temporaryFile)
        }
    }

    private fun setPermissions(target: File, permissions: Set<PosixFilePermission>) {
        try {
            Files.setPosixFilePermissions(target.toPath(), permissions)
        } catch (_: UnsupportedOperationException) {
        }
    }

    private companion object {
        val DIRECTORY_PERMISSIONS = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
        )
        val FILE_PERMISSIONS = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
        )
    }
}
