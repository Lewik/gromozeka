import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.gradle.nativeplatform.platform.internal.DefaultNativePlatform

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.jetbrains.compose)
}

val javaVersion = libs.versions.java.get().toInt()

kotlin {
    jvmToolchain(javaVersion)

    jvm {}
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
                implementation(project(":domain"))
                implementation(project(":remote-client"))

                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)

                implementation(libs.filekit.core)
                implementation(libs.filekit.compose)
                implementation(libs.multiplatform.markdown.renderer.m3)
                implementation(libs.multiplatform.markdown.renderer.code)
                implementation(libs.kotlinx.datetime)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.coroutines.core)
            }
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
                TargetFormat.Dmg,  // macOS universal
                TargetFormat.Msi,  // Windows
//                TargetFormat.Deb,  // Ubuntu/Debian
//                TargetFormat.Rpm,  // Red Hat/Fedora
//                TargetFormat.AppImage  // Linux AppImage (requires appimagetool)
            )
            
            packageName = "Gromozeka"
            packageVersion = rootProject.version.toString()
            description = "Multi-armed AI agent for comprehensive task automation"
            copyright = "© 2024 Gromozeka Project"
            vendor = "Gromozeka"
            
            appResourcesRootDir.set(project.layout.projectDirectory.dir("src/jvmMain/resources"))
            includeAllModules = true
            
            macOS {
                packageBuildVersion = rootProject.version.toString()
                dmgPackageVersion = rootProject.version.toString()
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
                console = true  // Enable console window for debugging
                
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
        systemProperty("gromozeka.project.root", rootProject.projectDir.absolutePath)
        System.getenv("GROMOZEKA_MODE")?.let {
            environment("GROMOZEKA_MODE", it)
        }
    }
}

// Enable zip64 for large JAR files (> 65535 entries)
tasks.withType<Zip>().configureEach {
    isZip64 = true
}
