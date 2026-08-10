plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.gromozeka.presentation.android"
    compileSdk = 37
    enableKotlin = false

    defaultConfig {
        applicationId = "com.gromozeka.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = rootProject.version.toString()
        manifestPlaceholders["gromozekaDefaultRemoteUrl"] = providers
            .gradleProperty("gromozeka.defaultRemoteUrl")
            .orElse("")
            .get()
        manifestPlaceholders["gromozekaEnableLocationTelemetry"] = providers
            .gradleProperty("gromozeka.android.location")
            .map { it.toBooleanStrictOrNull() ?: false }
            .orElse(false)
            .get()
            .toString()
    }
}

dependencies {
    implementation(project(":presentation"))
}
