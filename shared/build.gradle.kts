plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

val javaVersion = libs.versions.java.get().toInt()

kotlin {
    jvmToolchain(javaVersion)
    
    jvm {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    // Use only relevant opt-ins for shared module (no Compose APIs)
                    freeCompilerArgs.addAll(
                        "-opt-in=kotlin.time.ExperimentalTime",
                        "-opt-in=kotlinx.serialization.ExperimentalSerializationApi"
                    )
                }
            }
        }
    }
    
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.kotlinx.datetime)
                implementation(libs.kotlinx.serialization.json)
            }
        }
        
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        
        val jvmMain by getting {
            dependencies {
                implementation(libs.ktor.client.core)
            }
        }
        
        val jvmTest by getting {
            dependencies {
            }
        }
    }
}