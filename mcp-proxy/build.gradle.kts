plugins {
    kotlin("jvm")
    application
}

application {
    mainClass.set("McpProxyKt")
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib")
    implementation(libs.mcp.kotlin.sdk)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.io.core)
}

tasks.register<Jar>("fatJar") {
    archiveFileName.set("mcp-proxy.jar")
    manifest {
        attributes["Main-Class"] = "McpProxyKt"
    }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get().filter { it.name.endsWith("jar") }.map { zipTree(it) }
    })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

// Make main bot build depend on mcp-proxy fatJar
tasks.named("build") {
    dependsOn("fatJar")
}