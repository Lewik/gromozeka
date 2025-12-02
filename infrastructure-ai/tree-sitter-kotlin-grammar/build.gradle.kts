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
