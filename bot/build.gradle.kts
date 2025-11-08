import org.jetbrains.compose.desktop.application.dsl.TargetFormat

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
                implementation(libs.spring.ai.openai)
                implementation(libs.spring.ai.anthropic)
                // implementation(libs.spring.ai.vertex.ai.gemini)  // Replaced by spring-ai-google-genai
                implementation(libs.spring.ai.google.genai)
                implementation(libs.spring.ai.ollama)
                implementation(libs.spring.ai.starter.model.openai)
                implementation(libs.spring.ai.starter.mcp.client)

                // Reactor Kotlin extensions for Flux/Flow conversion
                implementation(libs.reactor.kotlin.extensions)

                implementation(compose.desktop.currentOs)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                implementation(libs.spring.boot.starter)
                

                // klog - Kotlin logging framework
                implementation(libs.klog)
                
                // FileKit for file picker
                implementation(libs.filekit.core)
                implementation(libs.filekit.compose)
                
                // Markdown renderer
                implementation(libs.multiplatform.markdown.renderer.m3)
                implementation(libs.multiplatform.markdown.renderer.code)
                
                implementation(project(":shared"))

                implementation(libs.kotlin.reflect)
                implementation(libs.kotlinx.coroutines.reactor)
                
                // Ktor Client
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.cio)
                implementation(libs.ktor.client.auth)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.json)
                
                // Ktor Server
                implementation(libs.ktor.server.core)
                implementation(libs.ktor.server.cio)
                implementation(libs.ktor.server.sse)
                implementation(libs.ktor.server.content.negotiation)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.xmlutil.serialization)
                implementation(libs.jackson.module.kotlin)

                // SQLite + Exposed ORM
                implementation(libs.sqlite.jdbc)
                implementation(libs.exposed.core)
                implementation(libs.exposed.dao)
                implementation(libs.exposed.jdbc)
                implementation(libs.exposed.kotlin.datetime)
                implementation(libs.exposed.migration.core)
                implementation(libs.exposed.migration.jdbc)

                // Flyway migrations
                implementation(libs.flyway.core)

                // MCP SDK for Gromozeka MCP tools (coexists with Java MCP SDK from Spring AI)
                implementation(libs.mcp.kotlin.sdk)

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

// Note: mcp-proxy JAR copying disabled - migrated to HTTP/SSE architecture
// The following tasks were removed:
// - copyMcpJarToResources 
// - jvmProcessResources dependency
// - build dependency on :mcp-proxy:fatJar
// JAR files are preserved in mcp-proxy/ directory for potential future use


// JAR archive name configuration removed - not applicable for KMP

compose.desktop {
    application {
        mainClass = "com.gromozeka.bot.ChatApplicationKt"
        jvmArgs += listOf(
            "-Xdock:icon=src/jvmMain/resources/logos/logo-256x256.png",
            "-Xdock:name=Gromozeka",
            "-Dapple.awt.application.appearance=system",
            
            // JNativeHook native library configuration for packaged apps
            "-Djava.library.path=\$APP_DIR/native-libs",
        )
        
        nativeDistributions {
            targetFormats(
                TargetFormat.Dmg,  // macOS universal
//                TargetFormat.Msi,  // Windows
//                TargetFormat.Deb,  // Ubuntu/Debian
//                TargetFormat.Rpm,  // Red Hat/Fedora
//                TargetFormat.AppImage  // Linux AppImage (requires appimagetool)
            )
            
            packageName = "Gromozeka"
            packageVersion = rootProject.version.toString()
            description = "Multi-armed AI agent for comprehensive task automation"
            copyright = "© 2024 Gromozeka Project"
            vendor = "Gromozeka"
            
            // Include native libraries and resources in the app bundle
            appResourcesRootDir.set(project.layout.projectDirectory.dir("src/jvmMain/resources"))
            
            // Include all JDK modules to ensure Java 21 compatibility
            includeAllModules = true
            
            macOS {
                packageBuildVersion = rootProject.version.toString()
                dmgPackageVersion = rootProject.version.toString()
                
                // Permissions for macOS
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
            
            // Windows and Linux distributions disabled
            /*
            windows {
                packageVersion = rootProject.version.toString()
            }
            
            linux {
                packageVersion = rootProject.version.toString()
            }
            */
        }
    }
}

tasks.register<Test>("convertLogo") {
    description = "Run only the logo generation test"
    group = "build"
    
    useJUnitPlatform()
    filter {
        includeTestsMatching("com.gromozeka.bot.LogoGenerationTest.generateLogos")
    }
    
    testClassesDirs = kotlin.jvm().compilations["test"].output.classesDirs
    classpath = kotlin.jvm().compilations["test"].runtimeDependencyFiles
    
    inputs.file("src/jvmMain/resources/logo.svg")
    outputs.dir("src/jvmMain/resources/logos")
}


tasks.register<Exec>("buildAppImage") {
    description = "Build AppImage distribution for Linux"
    group = "distribution"
    
    // Only run on Linux systems
    onlyIf {
        System.getProperty("os.name").lowercase().contains("linux")
    }
    
    workingDir = project.rootDir
    commandLine = listOf("bash", "./build-appimage.sh")
    
    // Set up proper dependencies
    dependsOn("build")
    
    // Define inputs and outputs for up-to-date checking
    inputs.files(
        "build-appimage.sh",
        "appimage-resources/AppRun", 
        "appimage-resources/gromozeka.desktop",
        "bot/src/jvmMain/resources/logos/logo-256x256.png"
    )
    inputs.dir("bot/src")
    inputs.dir("shared/src")
    inputs.files("bot/build.gradle.kts", "build.gradle.kts")
    
    outputs.dir("build/appimage")
    
    doFirst {
        if (!System.getProperty("os.name").lowercase().contains("linux")) {
            throw GradleException(
                "AppImage can only be built on Linux systems. " +
                "Current OS: ${System.getProperty("os.name")}\n" +
                "Use a Linux VM, container, or CI system to build AppImage."
            )
        }
        
        logger.lifecycle("Building AppImage for Gromozeka...")
        logger.lifecycle("This may take several minutes on first run...")
    }
    
    doLast {
        logger.lifecycle("AppImage build completed!")
        logger.lifecycle("Check build/appimage/ directory for the generated AppImage file")
    }
}

// Fix for "Invalid signature file digest" error from signed dependencies  
// Configuration cache compatible approach using Provider API
tasks.register("removeJarSignatures") {
    description = "Remove signature files from JAR to prevent SecurityException"
    group = "build"
    
    // This task should run after JAR creation
    dependsOn("packageUberJarForCurrentOS")
    
    // Use Provider API for configuration cache compatibility
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

// Configure run task to pass GROMOZEKA_MODE environment variable
tasks.named<JavaExec>("run") {
    // Only pass GROMOZEKA_MODE if it's set in the environment
    System.getenv("GROMOZEKA_MODE")?.let {
        environment("GROMOZEKA_MODE", it)
    }
}

tasks.register<JavaExec>("generateMigration") {
    description = "Generate Flyway migration from Exposed schema changes"
    group = "database"

    mainClass.set("com.gromozeka.bot.repository.exposed.GenerateMigrationKt")
    classpath = kotlin.jvm().compilations.getByName("main").runtimeDependencyFiles +
                kotlin.jvm().compilations.getByName("main").output.allOutputs

    dependsOn("jvmMainClasses")
}

