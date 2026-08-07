import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.gradle.nativeplatform.platform.internal.DefaultNativePlatform

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.android.application)
}

val javaVersion = libs.versions.java.get().toInt()
val nativePackageVersion = rootProject.version.toString()
    .substringBefore('-')
    .substringBefore('+')
    .let { version -> if (version == "0.0.0") "1.0.0" else version }
val hostOperatingSystem = DefaultNativePlatform.getCurrentOperatingSystem()
val localWorkerAppResources = layout.buildDirectory.dir("generated/local-worker-app-resources")
val localWorkerRuntimeResources = layout.buildDirectory.dir("generated/local-worker-runtime")
val macWorkerLauncher = rootProject.layout.buildDirectory.file(
    "native-launchers/macos-arm64/gromozeka-worker-launcher"
)
val workerBootJar = rootProject.layout.projectDirectory.file("worker/build/libs/gromozeka-worker.jar")
val bundledRuntimeManifest = rootProject.layout.projectDirectory.file(
    "deploy/distribution/bundled-runtime-versions.properties"
)
val bundledRuntimeScript = rootProject.layout.projectDirectory.file(
    "deploy/distribution/prepare-bundled-runtimes.sh"
)
val bundledRuntimePowerShellScript = rootProject.layout.projectDirectory.file(
    "deploy/distribution/prepare-bundled-runtimes.ps1"
)
val localWorkerRuntimeTarget = when {
    hostOperatingSystem.isMacOsX -> "macos" to "arm64"
    hostOperatingSystem.isWindows -> "windows" to "x64"
    else -> null
}
val bundledBrowserMcpResources = copySpec {
    from(rootProject.layout.projectDirectory.dir("browser-mcp")) {
        into("common/local-worker/app/browser-mcp")
        include(
            "package.json",
            "package-lock.json",
            "README.md",
            "NOTICE",
            "UPSTREAM.md",
            "THIRD_PARTY_NOTICES.txt",
            "LICENSE",
            "node_modules/**",
        )
        exclude("node_modules/.bin/**")
    }
    from(rootProject.layout.projectDirectory.file("THIRD_PARTY_NOTICES.md")) {
        into("common")
    }
}

val prepareLocalWorkerRuntimes by tasks.registering(Exec::class) {
    inputs.file(bundledRuntimeManifest)
    inputs.file(bundledRuntimeScript)
    inputs.file(bundledRuntimePowerShellScript)
    outputs.dir(localWorkerRuntimeResources)
    localWorkerRuntimeTarget?.let { target ->
        if (hostOperatingSystem.isWindows) {
            commandLine(
                "powershell.exe",
                "-NoProfile",
                "-ExecutionPolicy", "Bypass",
                "-File", bundledRuntimePowerShellScript.asFile.absolutePath,
                "-Component", "worker",
                "-Platform", target.first,
                "-Architecture", target.second,
                "-Destination", localWorkerRuntimeResources.get().asFile.absolutePath,
            )
        } else {
            commandLine(
                "bash",
                bundledRuntimeScript.asFile.absolutePath,
                "worker",
                target.first,
                target.second,
                localWorkerRuntimeResources.get().asFile.absolutePath,
            )
        }
    } ?: run {
        onlyIf { false }
        commandLine("true")
    }
}

val compileMacWorkerLauncher by tasks.registering(Exec::class) {
    inputs.file(rootProject.layout.projectDirectory.file("deploy/distribution/macos-worker-launcher.c"))
    outputs.file(macWorkerLauncher)
    macWorkerLauncher.get().asFile.parentFile.mkdirs()
    commandLine(
        "cc",
        "-Os",
        "-arch", "arm64",
        "-mmacosx-version-min=12.0",
        rootProject.layout.projectDirectory.file("deploy/distribution/macos-worker-launcher.c").asFile,
        "-framework", "CoreGraphics",
        "-framework", "ApplicationServices",
        "-o", macWorkerLauncher.get().asFile,
    )
}

val cleanLocalWorkerAppResources by tasks.registering(Delete::class) {
    delete(localWorkerAppResources)
}

val cleanPreparedAppResources by tasks.registering(Delete::class) {
    delete(layout.buildDirectory.dir("compose/tmp/prepareAppResources"))
}

val stageMacLocalWorkerResources by tasks.registering(Sync::class) {
    dependsOn(cleanLocalWorkerAppResources, compileMacWorkerLauncher, prepareLocalWorkerRuntimes, ":worker:bootJar")
    into(localWorkerAppResources)
    with(bundledBrowserMcpResources)
    from(workerBootJar) {
        into("common/local-worker/app")
    }
    from(localWorkerRuntimeResources) {
        into("common/local-worker/runtime")
    }
    from(rootProject.layout.projectDirectory.file("deploy/distribution/gromozeka-bundled-worker")) {
        into("common/local-worker/bin")
        rename { "gromozeka-worker" }
        filePermissions { unix("rwxr-xr-x") }
    }
    from(rootProject.layout.projectDirectory.file("deploy/distribution/gromozeka-worker-service")) {
        into("common/local-worker/bin")
        filePermissions { unix("rwxr-xr-x") }
    }
    from(rootProject.layout.projectDirectory.file("deploy/distribution/gromozeka-browser-mcp")) {
        into("common/local-worker/bin")
        filePermissions { unix("rwxr-xr-x") }
    }
    from(macWorkerLauncher) {
        into("common/local-worker/app/native")
        filePermissions { unix("rwxr-xr-x") }
    }
}

val stageWindowsLocalWorkerResources by tasks.registering(Sync::class) {
    dependsOn(cleanLocalWorkerAppResources, prepareLocalWorkerRuntimes, ":worker:bootJar")
    into(localWorkerAppResources)
    with(bundledBrowserMcpResources)
    from(workerBootJar) {
        into("common/local-worker/app")
    }
    from(localWorkerRuntimeResources) {
        into("common/local-worker/runtime")
    }
    from(rootProject.layout.projectDirectory.file("deploy/distribution/gromozeka-browser-mcp.cmd")) {
        into("common/local-worker/bin")
    }
    from(rootProject.layout.projectDirectory.file("deploy/distribution/gromozeka-browser-mcp.ps1")) {
        into("common/local-worker/bin")
    }
}

kotlin {
    jvmToolchain(javaVersion)

    jvm {}
    iosArm64 {
        binaries.framework {
            baseName = "GromozekaPresentation"
            binaryOption("bundleId", "com.gromozeka.presentation")
        }
    }
    iosSimulatorArm64 {
        binaries.framework {
            baseName = "GromozekaPresentation"
            binaryOption("bundleId", "com.gromozeka.presentation")
        }
    }
    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    wasmJs {
        outputModuleName = "gromozeka"
        browser {
            commonWebpackConfig {
                outputFileName = "gromozeka.js"
            }
        }
        binaries.executable()
    }
    
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":device-telemetry"))
                implementation(project(":domain"))
                implementation(project(":remote-client"))
                implementation(libs.ktor.client.core)
                implementation(project(":shared"))

                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                implementation(compose.components.resources)

                implementation(libs.multiplatform.markdown.renderer.m3)
                implementation(libs.multiplatform.markdown.renderer.code)
                implementation(libs.kotlinx.datetime)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.coroutines.core)
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }

        val iosMain by creating {
            dependsOn(commonMain)
        }
        val iosArm64Main by getting {
            dependsOn(iosMain)
        }
        val iosSimulatorArm64Main by getting {
            dependsOn(iosMain)
        }

        val jvmMain by getting {
            dependencies {
                implementation(project(":shared"))
                implementation(compose.desktop.currentOs)
            }
        }

        val wasmJsMain by getting {
            dependencies {
                implementation(libs.kotlinx.browser)
            }
        }

        val androidMain by getting {
            dependencies {
                implementation(libs.androidx.activity.compose)
            }
        }
    }
}

compose.resources {
    packageOfResClass = "com.gromozeka.presentation.resources"
}

android {
    namespace = "com.gromozeka.presentation"
    compileSdk = 36

    defaultConfig {
        val defaultRemoteUrl = providers.gradleProperty("gromozeka.defaultRemoteUrl")
            .orElse("")
            .get()

        applicationId = "com.gromozeka.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = rootProject.version.toString()
        buildConfigField("String", "DEFAULT_REMOTE_URL", "\"${defaultRemoteUrl.replace("\"", "\\\"")}\"")
        buildConfigField(
            "boolean",
            "ENABLE_LOCATION_TELEMETRY",
            providers.gradleProperty("gromozeka.android.location")
                .map { it.toBooleanStrictOrNull() ?: false }
                .orElse(false)
                .get()
                .toString()
        )
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(javaVersion)
    }
}

tasks.withType<Test> {
    useJUnitPlatform()

    // Compose desktop UI tests render offscreen, but AWT/Swing still initialize at JVM startup.
    // Without these flags local smoke tests can briefly create a transient app that steals focus
    // and interrupts whoever is working at the machine. Headless mode must be enabled before the
    // test JVM starts, and macOS UIElement keeps that transient process from surfacing as a real app.
    systemProperty("java.awt.headless", "true")

    val currentOs = DefaultNativePlatform.getCurrentOperatingSystem()
    if (currentOs.isMacOsX) {
        jvmArgs("-Dapple.awt.UIElement=true")
    }

    System.getProperty("gromozeka.realModelProbe")?.let {
        systemProperty("gromozeka.realModelProbe", it)
    }
    System.getProperty("gromozeka.realModelProbe.subscriptionConfig")?.let {
        systemProperty("gromozeka.realModelProbe.subscriptionConfig", it)
    }
    System.getProperty("gromozeka.realModelProbe.caseFilter")?.let {
        systemProperty("gromozeka.realModelProbe.caseFilter", it)
    }
    System.getProperty("gromozeka.realModelProbe.modelName")?.let {
        systemProperty("gromozeka.realModelProbe.modelName", it)
    }

    System.getProperty("gromozeka.memory.e2e")?.let {
        systemProperty("gromozeka.memory.e2e", it)
    }
    System.getProperty("gromozeka.memory.e2e.subscriptionConfig")?.let {
        systemProperty("gromozeka.memory.e2e.subscriptionConfig", it)
    }
    System.getProperty("gromozeka.memory.e2e.caseFilter")?.let {
        systemProperty("gromozeka.memory.e2e.caseFilter", it)
    }
    System.getProperty("gromozeka.memory.e2e.modelName")?.let {
        systemProperty("gromozeka.memory.e2e.modelName", it)
    }
    System.getProperty("gromozeka.llm.cassette.mode")?.let {
        systemProperty("gromozeka.llm.cassette.mode", it)
    }
    System.getProperty("gromozeka.llm.cassette.dir")?.let {
        systemProperty("gromozeka.llm.cassette.dir", it)
    }
    System.getProperty("gromozeka.llm.cassette.reportUnused")?.let {
        systemProperty("gromozeka.llm.cassette.reportUnused", it)
    }
    System.getProperty("gromozeka.llm.cassette.deleteUnused")?.let {
        systemProperty("gromozeka.llm.cassette.deleteUnused", it)
    }
}

compose.desktop {
    application {
        mainClass = "com.gromozeka.presentation.MainKt"
        
        // Platform-specific JVM arguments based on build OS
        // GitHub Actions builds each platform separately, so this works correctly:
        // - macOS runner (macos-latest) → macOS-specific args
        // - Windows runner (windows-latest) → Windows-specific args
        // - Linux runner (ubuntu-latest) → Linux-specific args
        val currentOs = DefaultNativePlatform.getCurrentOperatingSystem()
        when {
            currentOs.isMacOsX -> {
                jvmArgs += listOf(
                    "-Xdock:icon=\$APP_DIR/../Resources/logos/logo-256x256.png",
                    "-Xdock:name=Gromozeka",
                    "-Dapple.awt.application.appearance=system",
                    "-Djava.library.path=\$APP_DIR/native-libs"
                )
            }
            currentOs.isWindows -> {
                jvmArgs += listOf(
                    "-Djava.library.path=\$APP_DIR/native-libs",
                    "-Dfile.encoding=UTF-8"
                )
            }
            currentOs.isLinux -> {
                jvmArgs += listOf(
                    "-Djava.library.path=\$APP_DIR/native-libs",
                    "-Dfile.encoding=UTF-8"
                )
            }
        }
        
        nativeDistributions {
            targetFormats(
                TargetFormat.Dmg,
            )
            
            packageName = "Gromozeka"
            packageVersion = nativePackageVersion
            description = "Multi-armed AI agent for comprehensive task automation"
            copyright = "© 2024 Gromozeka Project"
            vendor = "Gromozeka"
            
            if (hostOperatingSystem.isMacOsX || hostOperatingSystem.isWindows) {
                appResourcesRootDir.set(localWorkerAppResources)
            } else {
                appResourcesRootDir.set(project.layout.projectDirectory.dir("src/jvmMain/resources"))
            }
            licenseFile.set(rootProject.layout.projectDirectory.file("LICENSE"))
            includeAllModules = true
            
            macOS {
                packageBuildVersion = nativePackageVersion
                dmgPackageVersion = nativePackageVersion
                bundleID = "com.gromozeka.app"
                
                // macOS-specific JVM args (Dock, appearance) are set via OS detection above
                
                infoPlist {
                    extraKeysRawXml = """
                        <key>NSMicrophoneUsageDescription</key>
                        <string>Gromozeka needs microphone access for voice input and speech-to-text functionality</string>
                        <key>NSAccessibilityUsageDescription</key>
                        <string>Gromozeka may request accessibility access for future global hotkey functionality</string>
                        <key>NSInputMonitoringUsageDescription</key>
                        <string>Gromozeka may request input monitoring access for future global hotkey functionality</string>
                    """
                }
                
                signing {
                    sign.set(false)
                }
            }
            
            windows {
                menuGroup = "Gromozeka"
                perUserInstall = true
                dirChooser = true
                upgradeUuid = "1e5a8b2c-3d4e-5f6a-7b8c-9d0e1f2a3b4c"
                console = false
                
                // Windows-specific JVM args are set via OS detection above
            }
        }
    }
}

tasks.register("removeJarSignatures") {
    description = "Remove signature files from JAR to prevent SecurityException"
    group = "build"
    
    dependsOn("packageUberJarForCurrentOS")
    
    val jarDir = layout.buildDirectory.dir("compose/jars")
    inputs.dir(jarDir)
    
    doLast {
        val jarsDir = jarDir.get().asFile
        val jarFiles = jarsDir.listFiles { _, name -> name.endsWith(".jar") } ?: emptyArray()
        
        if (jarFiles.isEmpty()) {
            logger.warn("No JAR files found in ${jarsDir.absolutePath}")
            return@doLast
        }
        
        jarFiles.forEach { jarFile ->
            logger.lifecycle("Removing signature files from: ${jarFile.name}")
            
            try {
                val process = ProcessBuilder(
                    "zip", "-d", jarFile.absolutePath,
                    "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA"
                ).start()
                
                val exitCode = process.waitFor()
                when (exitCode) {
                    0 -> logger.info("Successfully removed signature files from ${jarFile.name}")
                    12 -> logger.info("No signature files found in ${jarFile.name} (expected)")
                    else -> logger.warn("zip command returned exit code $exitCode for ${jarFile.name}")
                }
            } catch (e: Exception) {
                logger.error("Failed to remove signatures from ${jarFile.name}: ${e.message}")
            }
        }
    }
}

tasks.whenTaskAdded {
    if (name == "run" && this is JavaExec) {
        when {
            hostOperatingSystem.isMacOsX -> dependsOn(stageMacLocalWorkerResources)
            hostOperatingSystem.isWindows -> dependsOn(stageWindowsLocalWorkerResources)
        }
        if (hostOperatingSystem.isMacOsX || hostOperatingSystem.isWindows) {
            systemProperty(
                "gromozeka.local-worker.bundle-root",
                localWorkerAppResources.get().dir("common/local-worker").asFile.absolutePath,
            )
        }
        systemProperty("gromozeka.project.root", rootProject.projectDir.absolutePath)
        systemProperty(
            "gromozeka.remote.url",
            System.getProperty("gromozeka.remote.url")
                ?: System.getenv("GROMOZEKA_REMOTE_URL")
                ?: providers.gradleProperty("gromozeka.defaultRemoteUrl").orNull
                ?: "ws://127.0.0.1:8765/ws",
        )
        System.getenv("GROMOZEKA_MODE")?.let {
            environment("GROMOZEKA_MODE", it)
        }
    }
}

tasks.matching {
    it.name == "prepareAppResources" ||
        it.name == "createDistributable" ||
        it.name == "packageDmg"
}.configureEach {
    if (name == "prepareAppResources") {
        dependsOn(cleanPreparedAppResources)
    }
    when {
        hostOperatingSystem.isMacOsX -> dependsOn(stageMacLocalWorkerResources)
        hostOperatingSystem.isWindows -> dependsOn(stageWindowsLocalWorkerResources)
    }
}

val restoreMacLocalWorkerExecutablePermissions by tasks.registering(Exec::class) {
    mustRunAfter("createDistributable")
    if (hostOperatingSystem.isMacOsX) {
        val script = rootProject.layout.projectDirectory.file(
            "deploy/distribution/restore-bundled-executables.sh"
        )
        inputs.file(script)
        inputs.dir(localWorkerAppResources)
        commandLine(
            "bash",
            script.asFile.absolutePath,
            localWorkerAppResources.get().dir("common/local-worker").asFile.absolutePath,
            layout.buildDirectory.dir("compose/binaries").get().asFile.absolutePath,
        )
    } else if (hostOperatingSystem.isWindows) {
        commandLine("cmd", "/c", "exit", "0")
    } else {
        commandLine("true")
    }
}

tasks.matching { it.name == "createDistributable" }.configureEach {
    finalizedBy(restoreMacLocalWorkerExecutablePermissions)
}

tasks.matching { it.name == "packageDmg" }.configureEach {
    dependsOn(restoreMacLocalWorkerExecutablePermissions)
}

// Enable zip64 for large JAR files (> 65535 entries)
tasks.withType<Zip>().configureEach {
    isZip64 = true
}
