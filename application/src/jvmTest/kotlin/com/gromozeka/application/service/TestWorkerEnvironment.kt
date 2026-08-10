package com.gromozeka.application.service

import com.gromozeka.domain.service.WorkerEnvironmentProfile
import com.gromozeka.domain.service.WorkerNativeShell
import com.gromozeka.domain.service.WorkerOperatingSystem
import kotlin.time.Instant

fun testWorkerEnvironmentProfile(
    observedAt: Instant = Instant.fromEpochMilliseconds(1),
): WorkerEnvironmentProfile =
    WorkerEnvironmentProfile(
        observedAt = observedAt,
        operatingSystem = WorkerOperatingSystem(
            family = WorkerOperatingSystem.Family.LINUX,
            name = "Test Linux",
            version = "1",
        ),
        architecture = "x86_64",
        nativeShell = WorkerNativeShell(
            kind = WorkerNativeShell.Kind.POSIX_SH,
            executable = "/bin/sh",
        ),
        timezoneId = "UTC",
        localeTag = "en-US",
        logicalProcessorCount = 4,
        totalMemoryBytes = 8_589_934_592,
        availableExecutables = listOf("git", "sh"),
    )
