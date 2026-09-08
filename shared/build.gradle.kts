import groovy.json.JsonOutput
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

val javaVersion = libs.versions.java.get().toInt()
val localizationDirectory = rootProject.layout.projectDirectory.dir("localization")
val localizationSources = layout.buildDirectory.dir("generated/localization/kotlin")
val localizationResources = layout.buildDirectory.dir("generated/localization/resources")
val localizationCatalogs = fileTree(localizationDirectory) {
    include("*.json")
    exclude("context.json", "glossary.json", "package.schema.json")
}

@CacheableTask
abstract class GenerateLocalizationBundle : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val catalogFiles: ConfigurableFileCollection

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val pluralData: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val pluralLicense: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val translationGuidance: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val sourceDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val resourceDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        fun literal(value: String) = JsonOutput.toJson(value).replace("$", "\\$")
        fun chunks(value: String) = value.chunked(8_000).joinToString(",\n") { "            ${literal(it)}" }
        val catalogs = catalogFiles.files.sortedBy { it.name }
        val generated = sourceDirectory.get().file(
            "com/gromozeka/shared/localization/BundledLocalizationData.kt"
        ).asFile
        generated.parentFile.mkdirs()
        generated.writeText(buildString {
            appendLine("package com.gromozeka.shared.localization")
            appendLine()
            appendLine("internal object BundledLocalizationData {")
            appendLine("    val locales = listOf(${catalogs.joinToString { literal(it.nameWithoutExtension) }})")
            appendLine("    fun catalog(locale: String): String = when (locale) {")
            catalogs.forEachIndexed { index, file ->
                appendLine("        ${literal(file.nameWithoutExtension)} -> catalog$index()")
            }
            appendLine("        else -> error(\"Unknown bundled locale: \" + locale)")
            appendLine("    }")
            catalogs.forEachIndexed { index, file ->
                appendLine("    private fun catalog$index(): String = listOf(")
                appendLine(chunks(file.readText()))
                appendLine("    ).joinToString(\"\")")
            }
            appendLine("    fun cardinalRules(): String = listOf(")
            appendLine(chunks(pluralData.get().asFile.readText()))
            appendLine("    ).joinToString(\"\")")
            translationGuidance.files.sortedBy { it.name }.forEach { file ->
                val name = when (file.name) {
                    "context.json" -> "context"
                    "glossary.json" -> "glossary"
                    "translator-prompt.md" -> "translatorPrompt"
                    else -> error("Unknown translation guidance: ${file.name}")
                }
                appendLine("    fun $name(): String = listOf(")
                appendLine(chunks(file.readText()))
                appendLine("    ).joinToString(\"\")")
            }
            appendLine("}")
        })
        val notice = resourceDirectory.get().file("licenses/Unicode-CLDR.txt").asFile
        notice.parentFile.mkdirs()
        pluralLicense.get().asFile.copyTo(notice, overwrite = true)
    }
}

val generateLocalizationBundle by tasks.registering(GenerateLocalizationBundle::class) {
    catalogFiles.from(localizationCatalogs)
    pluralData.set(localizationDirectory.file("cldr/plurals.json"))
    pluralLicense.set(localizationDirectory.file("cldr/LICENSE"))
    translationGuidance.from(listOf("context.json", "glossary.json", "translator-prompt.md").map(localizationDirectory::file))
    sourceDirectory.set(localizationSources)
    resourceDirectory.set(localizationResources)
}

tasks.withType<KotlinCompilationTask<*>>().configureEach {
    dependsOn(generateLocalizationBundle)
}
tasks.matching { it.name.contains("resources", ignoreCase = true) }.configureEach {
    dependsOn(generateLocalizationBundle)
}

kotlin {
    jvmToolchain(javaVersion)
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    jvm {}
    iosArm64()
    iosSimulatorArm64()
    android {
        namespace = "com.gromozeka.shared"
        compileSdk = 37
        minSdk = 24
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    wasmJs {
        browser()
    }
    
    sourceSets {
        val commonMain by getting {
            kotlin.srcDir(localizationSources)
            resources.srcDir(localizationResources)
            dependencies {
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.datetime)
                implementation(libs.kotlinx.coroutines.core)
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
        
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        
        val jvmMain by getting {
            dependencies {
                implementation(libs.uuid.creator)
            }
        }
        
        val jvmTest by getting {
            dependencies {
            }
        }
    }
}
