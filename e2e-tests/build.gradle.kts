import org.gradle.nativeplatform.platform.internal.DefaultNativePlatform

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.jetbrains.compose)
}

val javaVersion = libs.versions.java.get().toInt()
val serverJar = rootProject.layout.projectDirectory.file("server/build/libs/gromozeka-server.jar")

kotlin {
    jvmToolchain(javaVersion)
    compilerOptions {
        optIn.add("androidx.compose.ui.test.ExperimentalTestApi")
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(javaVersion)
    }
}

dependencies {
    testImplementation(project(":domain"))
    testImplementation(project(":presentation"))
    testImplementation(project(":remote-client"))
    testImplementation(project(":remote-protocol"))
    testImplementation(compose.desktop.currentOs)
    testImplementation(libs.compose.ui.test)
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.ktor.client.core)
    testImplementation(libs.postgresql.jdbc)
    testImplementation(kotlin("test-junit5"))
}

tasks.test {
    dependsOn(":server:bootJar", ":presentation:jvmTest")
    inputs.file(serverJar)
    outputs.upToDateWhen { false }

    useJUnitPlatform()
    failFast = true
    maxParallelForks = providers.environmentVariable("GROMOZEKA_E2E_FORKS")
        .map(String::toInt)
        .orElse(1)
        .get()
        .coerceAtLeast(1)

    systemProperty("java.awt.headless", "true")
    systemProperty("gromozeka.e2e.serverJar", serverJar.asFile.absolutePath)
    systemProperty(
        "gromozeka.e2e.artifactsDir",
        providers.environmentVariable("GROMOZEKA_E2E_ARTIFACTS_DIR")
            .orElse(layout.buildDirectory.dir("e2e-artifacts").map { it.asFile.absolutePath })
            .get(),
    )

    if (DefaultNativePlatform.getCurrentOperatingSystem().isMacOsX) {
        jvmArgs("-Dapple.awt.UIElement=true")
    }

    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
