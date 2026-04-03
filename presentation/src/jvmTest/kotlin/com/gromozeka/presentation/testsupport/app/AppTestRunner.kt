package com.gromozeka.presentation.testsupport.app

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.runComposeUiTest
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val artifactTimestampFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
private val testArtifactsDirectory: Path = Path.of("build", "test-artifacts")

/**
 * Runs a test block inside a started desktop app with trace and failure artifacts.
 */
fun withAppTestContext(
    owner: Any,
    testName: String,
    harness: AppTestHarness = AppTestHarness(),
    skipLoadingScreen: Boolean = true,
    block: AppTestActions.() -> Unit,
) {
    val ownerName = owner.javaClass.simpleName.ifBlank { "UnknownTest" }
    harness.use { startedHarness ->
        runComposeUiTest {
            val traceDirectory = testArtifactsDirectory
                .resolve(".tmp")
                .resolve(ownerName.sanitizePathSegment())
                .resolve("${testName.sanitizePathSegment()}-${LocalDateTime.now().format(artifactTimestampFormatter)}")
            Files.createDirectories(traceDirectory)
            val trace = AppTestTrace(traceDirectory)

            try {
                val defaultRootScreenshotPath = testArtifactsDirectory
                    .resolve("screenshots")
                    .resolve("${testName.sanitizePathSegment()}.png")
                setContent {
                    startedHarness.Content(skipLoadingScreen = skipLoadingScreen)
                }
                AppTestActions(
                    composeUiTest = this,
                    trace = trace,
                    defaultRootScreenshotPath = defaultRootScreenshotPath,
                ).block()
            } catch (throwable: Throwable) {
                runCatching { trace.capture(this, "failure-final") }
                persistFailureArtifacts(
                    testClass = ownerName,
                    testName = testName,
                    harness = startedHarness,
                    trace = trace,
                    throwable = throwable,
                )
                throw throwable
            } finally {
                trace.cleanup()
            }
        }
    }
}

private fun ComposeUiTest.persistFailureArtifacts(
    testClass: String,
    testName: String,
    harness: AppTestHarness,
    trace: AppTestTrace,
    throwable: Throwable,
) {
    val artifactDirectory = testArtifactsDirectory
        .resolve("failures")
        .resolve(testClass.sanitizePathSegment())
        .resolve("${testName.sanitizePathSegment()}-${LocalDateTime.now().format(artifactTimestampFormatter)}")

    runCatching {
        Files.createDirectories(artifactDirectory)
        runCatching {
            copyDirectory(trace.directory, artifactDirectory.resolve("trace"))
        }
        runCatching {
            copyDirectory(harness.homeDirectory, artifactDirectory.resolve("home"))
        }
        runCatching {
            Files.writeString(artifactDirectory.resolve("failure.txt"), throwable.stackTraceToString())
        }
    }
}
