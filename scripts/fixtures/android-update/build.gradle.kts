plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.gromozeka.updatefixture"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.gromozeka.updatefixture"
        minSdk = 26
        targetSdk = 35
        versionCode = providers.gradleProperty("updateFixtureVersion").getOrElse("1").toInt()
        versionName = "$versionCode.0.0"
    }
}

dependencies {
    implementation(project(":application-updates"))
    implementation(libs.ktor.client.mock)
    implementation(libs.kotlinx.serialization.json)
}
