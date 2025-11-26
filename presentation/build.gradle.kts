import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.gradle.nativeplatform.platform.internal.DefaultNativePlatform

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.jetbrains.compose)
}

// Force Kotlin version override for Spring Boot 3.4.4 (uses 1.9.25 by default)
extra["kotlin.version"] = libs.versions.kotlin.get()

// Override kotlinx-serialization version from Spring Boot BOM (1.6.3 -> 1.9.0)
extra["kotlin-serialization.version"] = libs.versions.kotlinx.serialization.get()

val javaVersion = libs.versions.java.get().toInt()

kotlin {
    jvmToolchain(javaVersion)

    jvm {}
    
    sourceSets {
        val jvmMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                implementation(libs.spring.boot.starter)
                
                // Dependencies on other modules
                implementation(project(":domain"))  // Transitively provides :shared
                implementation(project(":application"))
                implementation(project(":infrastructure-db"))
                implementation(project(":infrastructure-ai"))

                // klog - Kotlin logging framework
                implementation(libs.klog)
                
                // FileKit for file picker
                implementation(libs.filekit.core)
                implementation(libs.filekit.compose)
                
                // Markdown renderer
                implementation(libs.multiplatform.markdown.renderer.m3)
                implementation(libs.multiplatform.markdown.renderer.code)

                implementation(libs.kotlin.reflect)
                implementation(libs.kotlinx.coroutines.reactor)
                implementation(libs.kotlinx.datetime)
                
                // Spring AI providers
                implementation(libs.spring.ai.openai)
                implementation(libs.spring.ai.starter.model.openai)
                implementation(libs.spring.ai.anthropic)
                implementation(libs.spring.ai.google.genai)
                implementation(libs.spring.ai.ollama)
                
                // MCP SDK for internal tools
                implementation(libs.mcp.kotlin.sdk)
                
                // Flyway (for DatabaseBackupCallback)
                implementation(libs.flyway.core)
                
                // Ktor Client
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.cio)
                implementation(libs.ktor.client.auth)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.json)
                
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.xmlutil.serialization)
                implementation(libs.jackson.module.kotlin)

                // Age encryption for secure log packaging
                implementation(libs.jagged.api)
                implementation(libs.jagged.x25519)
                implementation(libs.jagged.framework)
                
                // Batik for logo generation task
                implementation(libs.batik.transcoder)
                implementation(libs.batik.codec)

                // Apache PDFBox for PDF parsing
                implementation(libs.pdfbox)
            }
        }
        
        val jvmTest by getting {
            dependencies {
                implementation(libs.spring.boot.starter.test)
                implementation(libs.mockk)
                implementation(libs.kotlinx.serialization.json)
                implementation(kotlin("test"))
                
                // Batik for SVG to PNG conversion (build-time only)
                implementation(libs.batik.transcoder)
                implementation(libs.batik.codec)
            }
        }
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(javaVersion)
    }
}

dependencyManagement {
    imports {
        mavenBom(libs.spring.ai.bom.get().toString())
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
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
