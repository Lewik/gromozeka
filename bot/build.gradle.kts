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
    
    jvm {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    freeCompilerArgs.addAll(project.extra["experimentalOptIns"] as List<String>)
                }
            }
        }
    }
    
    sourceSets {
        val jvmMain by getting {
            dependencies {
                implementation(libs.spring.ai.openai)
                implementation(libs.spring.ai.anthropic)
                implementation(libs.spring.ai.starter.model.openai)
                
                implementation(compose.desktop.currentOs)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                implementation(libs.spring.boot.starter)
                
                // Janino for Logback conditional processing
                implementation("org.codehaus.janino:janino:3.1.12")

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
                implementation(libs.kotlinx.datetime)
                
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
                
                
                implementation(libs.jnativehook)
                
                // MCP SDK for official protocol structures
                implementation(libs.mcp.kotlin.sdk)
                
                // Batik for logo generation task
                implementation(libs.batik.transcoder)
                implementation(libs.batik.codec)
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


compose.desktop {
    application {
        mainClass = "com.gromozeka.bot.ChatApplicationKt"
        jvmArgs += listOf(
            "-Xdock:icon=src/jvmMain/resources/logos/logo-256x256.png",
            "-Xdock:name=Gromozeka",
            "-Dapple.awt.application.appearance=system",
            
            // JNativeHook native library configuration for packaged apps
            "-Djava.library.path=\$APP_DIR/native-libs",
            "-Djnativehook.lib.path=\$APP_DIR/native-libs"
        )
        
        nativeDistributions {
            targetFormats(
                TargetFormat.Dmg,  // macOS universal
//                TargetFormat.Msi,  // Windows
//                TargetFormat.Deb,  // Ubuntu/Debian
//                TargetFormat.Rpm   // Red Hat/Fedora
            )
            
            packageName = "Gromozeka"
            packageVersion = rootProject.version.toString()
            description = "Multi-armed AI agent for comprehensive task automation"
            copyright = "© 2024 Gromozeka Project"
            vendor = "Gromozeka"
            
            // Include native libraries and resources in the app bundle
            appResourcesRootDir.set(project.layout.projectDirectory.dir("bot/src/jvmMain/resources"))
            
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
                        <string>Gromozeka needs accessibility access for global hotkeys (§ key for push-to-talk)</string>
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

val extractNativeLibraries = tasks.register("extractNativeLibraries") {
    description = "Extract JNativeHook native libraries for packaged applications"
    group = "build"
    
    val nativeLibsDir = layout.projectDirectory.dir("src/jvmMain/resources/native-libs")
    
    inputs.files(configurations.named("jvmRuntimeClasspath"))
    outputs.dir(nativeLibsDir)
    
    doLast {
        val runtimeClasspath = configurations.getByName("jvmRuntimeClasspath")
        val jnativeHookJar = runtimeClasspath.files
            .find { it.name.contains("jnativehook") && it.name.endsWith(".jar") }
        
        if (jnativeHookJar == null) {
            throw GradleException("JNativeHook JAR not found in dependencies")
        }
        
        println("Extracting JNativeHook native libraries for macOS...")
        
        val tempDir = layout.buildDirectory.dir("tmp/jnativehook").get()
        
        // Extract native libraries using copy
        copy {
            from(zipTree(jnativeHookJar)) {
                include("com/github/kwhat/jnativehook/lib/darwin/**/*.dylib")
            }
            into(tempDir)
        }
        
        // Move to target location with proper names
        val tempDirFile = tempDir.asFile
        val x86File = File(tempDirFile, "com/github/kwhat/jnativehook/lib/darwin/x86_64/libJNativeHook.dylib")
        val armFile = File(tempDirFile, "com/github/kwhat/jnativehook/lib/darwin/arm64/libJNativeHook.dylib")
        
        val nativeLibsDirFile = nativeLibsDir.asFile
        nativeLibsDirFile.mkdirs()
        
        if (x86File.exists()) {
            x86File.copyTo(File(nativeLibsDirFile, "libJNativeHook-x86_64.dylib"), true)
            println("Extracted x86_64 library")
        }
        
        if (armFile.exists()) {
            armFile.copyTo(File(nativeLibsDirFile, "libJNativeHook-arm64.dylib"), true)
            println("Extracted arm64 library")
        }
        
        // Clean up temp directory
        delete(tempDir)
    }
}

// Автоматически извлекать нативные библиотеки перед обработкой ресурсов и сборкой DMG
afterEvaluate {
    tasks.findByName("jvmProcessResources")?.dependsOn(extractNativeLibraries)
    tasks.findByName("createDistributable")?.dependsOn(extractNativeLibraries)
    tasks.findByName("packageDistributionForCurrentOS")?.dependsOn(extractNativeLibraries)
    tasks.findByName("packageDmg")?.dependsOn(extractNativeLibraries)
}
