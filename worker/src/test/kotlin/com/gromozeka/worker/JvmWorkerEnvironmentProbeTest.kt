package com.gromozeka.worker

import com.gromozeka.domain.model.Workspace
import com.gromozeka.domain.model.WorkspaceMount
import com.gromozeka.domain.service.WorkerNativeShell
import com.gromozeka.domain.service.WorkerOperatingSystem
import com.gromozeka.domain.service.WorkerWorkspaceStorage
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JvmWorkerEnvironmentProbeTest {
    private val observedAt = Instant.parse("2026-07-28T10:00:00Z")

    @Test
    fun `darwin is reported as macos and not windows`() {
        val host = host(osName = "Darwin")
        val profile = probe(host).collectProfile()

        assertEquals(WorkerOperatingSystem.Family.MACOS, profile.operatingSystem.family)
        assertEquals(WorkerNativeShell.Kind.POSIX_SH, profile.nativeShell.kind)
        assertEquals("/bin/sh", profile.nativeShell.executable)
    }

    @Test
    fun `windows profile normalizes architecture and reports native command shell`() {
        val host = host(
            osName = "Windows 11",
            architecture = "AMD64",
            availableExecutables = mutableSetOf("git", "pwsh"),
            windowsCommandInterpreter = "C:\\Windows\\System32\\cmd.exe",
        )
        val profile = probe(host).collectProfile()

        assertEquals(WorkerOperatingSystem.Family.WINDOWS, profile.operatingSystem.family)
        assertEquals("x86_64", profile.architecture)
        assertEquals(WorkerNativeShell.Kind.WINDOWS_CMD, profile.nativeShell.kind)
        assertEquals("C:\\Windows\\System32\\cmd.exe", profile.nativeShell.executable)
        assertEquals(listOf("git", "pwsh"), profile.availableExecutables)
    }

    @Test
    fun `requested snapshot recollects the complete profile`() {
        val availableExecutables = mutableSetOf("git")
        val host = host(availableExecutables = availableExecutables)
        val probe = probe(host)
        val registeredProfile = probe.collectProfile()

        availableExecutables += "jq"
        host.availableMemoryBytes = 4_096
        val snapshot = probe.collectSnapshot(
            workspaceMounts = listOf(mount("mount-b"), mount("mount-a")),
            requestedExecutableNames = setOf("missing", "jq"),
        )

        assertEquals(listOf("git"), registeredProfile.availableExecutables)
        assertEquals(listOf("git", "jq"), snapshot.profile.availableExecutables)
        assertEquals(4_096, snapshot.availableMemoryBytes)
        assertEquals(
            listOf("jq" to true, "missing" to false),
            snapshot.requestedExecutables.map { it.name to it.available },
        )
        assertEquals(listOf("mount-a", "mount-b"), snapshot.workspaceStorage.map { it.workspaceMountId.value })
    }

    @Test
    fun `inaccessible workspace does not invent storage capacity`() {
        val host = host()
        host.inaccessibleMounts += "offline"

        val snapshot = probe(host).collectSnapshot(
            workspaceMounts = listOf(mount("offline")),
            requestedExecutableNames = emptySet(),
        )

        val storage = snapshot.workspaceStorage.single()
        assertFalse(storage.accessible)
        assertEquals(null, storage.totalBytes)
        assertEquals(null, storage.usableBytes)
    }

    @Test
    fun `snapshot discards invalid volatile host measurements`() {
        val host = host()
        host.availableMemoryBytes = -1
        host.systemCpuLoad = Double.NaN
        host.workerProcessUptimeMillis = -1

        val snapshot = probe(host).collectSnapshot(emptyList(), emptySet())

        assertEquals(null, snapshot.availableMemoryBytes)
        assertEquals(null, snapshot.systemCpuLoadRatio)
        assertEquals(0, snapshot.workerProcessUptimeMillis)
        assertTrue(snapshot.workspaceStorage.isEmpty())
    }

    @Test
    fun `real host produces a usable profile and snapshot`() {
        val probe = JvmWorkerEnvironmentProbe(emptySet())

        val snapshot = probe.collectSnapshot(emptyList(), emptySet())

        assertTrue(snapshot.profile.operatingSystem.name.isNotBlank())
        assertTrue(snapshot.profile.architecture.isNotBlank())
        assertTrue(snapshot.profile.logicalProcessorCount > 0)
        assertTrue(snapshot.workerProcessUptimeMillis >= 0)
    }

    private fun probe(host: FakeWorkerHost): JvmWorkerEnvironmentProbe =
        JvmWorkerEnvironmentProbe(
            executableNames = setOf("jq", "git", "pwsh"),
            host = host,
            now = { observedAt },
        )

    private fun host(
        osName: String = "Linux",
        architecture: String = "aarch64",
        availableExecutables: MutableSet<String> = mutableSetOf(),
        windowsCommandInterpreter: String = "cmd.exe",
    ): FakeWorkerHost =
        FakeWorkerHost(
            osName = osName,
            osVersion = "test-version",
            architecture = architecture,
            timezoneId = "Asia/Jerusalem",
            localeTag = "en-US",
            logicalProcessorCount = 8,
            windowsCommandInterpreter = windowsCommandInterpreter,
            totalMemoryBytes = 16_384,
            availableMemoryBytes = 8_192,
            systemCpuLoad = 0.25,
            workerProcessUptimeMillis = 10_000,
            availableExecutables = availableExecutables,
        )

    private fun mount(id: String): WorkspaceMount =
        WorkspaceMount(
            id = WorkspaceMount.Id(id),
            workspaceId = Workspace.Id("workspace-$id"),
            workerId = "worker-1",
            rootPath = "/workspace/$id",
            createdAt = observedAt,
            updatedAt = observedAt,
        )

    private class FakeWorkerHost(
        override val osName: String,
        override val osVersion: String,
        override val architecture: String,
        override val timezoneId: String,
        override val localeTag: String,
        override val logicalProcessorCount: Int,
        override val windowsCommandInterpreter: String,
        override val totalMemoryBytes: Long?,
        override var availableMemoryBytes: Long?,
        override var systemCpuLoad: Double?,
        override var workerProcessUptimeMillis: Long,
        private val availableExecutables: MutableSet<String>,
    ) : WorkerHost {
        val inaccessibleMounts = mutableSetOf<String>()

        override fun isExecutableAvailable(name: String): Boolean =
            name in availableExecutables

        override fun workspaceStorage(mount: WorkspaceMount): WorkerWorkspaceStorage {
            val accessible = mount.id.value !in inaccessibleMounts
            return WorkerWorkspaceStorage(
                workspaceMountId = mount.id,
                rootPath = mount.rootPath,
                accessible = accessible,
                totalBytes = 100L.takeIf { accessible },
                usableBytes = 40L.takeIf { accessible },
            )
        }
    }
}
