import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("multiplatform") version "2.2.0"
    id("io.github.tree-sitter.ktreesitter-plugin") version "0.24.1"
}

val grammarDir = projectDir.resolve("../build/tree-sitter-kotlin")

grammar {
    baseDir = grammarDir
    grammarName = "kotlin"
    className = "TreeSitterKotlin"
    packageName = "io.github.treesitter.ktreesitter.kotlin"
    files = arrayOf(
        grammarDir.resolve("src/parser.c"),
        grammarDir.resolve("src/scanner.c")
    )
}

// Fix Windows path escaping in generated CMakeLists.txt
val fixCMakePaths by tasks.registering {
    dependsOn("generateGrammarFiles")
    
    doLast {
        val cmakeFile = file("build/generated/CMakeLists.txt")
        if (cmakeFile.exists()) {
            val grammarDirAbsolute = grammarDir.absolutePath.replace("\\", "/")
            val content = cmakeFile.readText()
                .replace("../../../build/tree-sitter-kotlin", grammarDirAbsolute)
            cmakeFile.writeText(content)
            println("Fixed CMakeLists.txt paths for cross-platform compatibility")
        }
    }
}

tasks.named("generateGrammarFiles") {
    finalizedBy(fixCMakePaths)
}

kotlin {
    jvmToolchain(21)
    
    jvm {}
    
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("io.github.tree-sitter:ktreesitter:0.24.1")
            }
        }
    }
}
