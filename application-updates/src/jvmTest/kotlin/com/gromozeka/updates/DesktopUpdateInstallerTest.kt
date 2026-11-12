package com.gromozeka.updates

import com.gromozeka.domain.model.*
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class DesktopUpdateInstallerTest {
    @Test fun `installer liveness checks both pid and process start identity`() = runBlocking {
        val directory = Files.createTempDirectory("installer-identity-").toFile()
        try {
            val update = ApplicationUpdateRequest("identity-test", ApplicationUpdateArtifact(
                UpdateComponent.CLIENT, UpdatePlatform.MACOS_ARM64, "1.2.3", "app.zip", 1, "a".repeat(64), UpdatePackageFormat.MACOS_APP_ZIP,
            ), "2026-01-01T00:00:00Z")
            val store = UpdateLocalStore(directory)
            val installer = DesktopUpdateInstaller(UpdateComponent.CLIENT, directory)
            File(directory, "handoff-${update.id}").writeText((System.currentTimeMillis() - 180_000).toString())
            val process = ProcessHandle.current()
            val started = process.info().startInstant().orElseThrow().epochSecond
            val identity = File(directory, "installer-${update.id}.process")
            identity.writeText("${process.pid()} $started\n")
            assertFalse(installer.installationStopped(update, store))
            identity.writeText("${process.pid()} ${started - 1}\n")
            assertTrue(installer.installationStopped(update, store))
            identity.writeText("${process.pid()}\n")
            assertTrue(installer.installationStopped(update, store))
            identity.delete()
            assertTrue(installer.installationStopped(update, store))
        } finally { directory.deleteRecursively() }
    }
}
