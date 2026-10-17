plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "it.snel.melamur"
    compileSdk = 35
    defaultConfig {
        applicationId = "it.snel.melamur"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
        // Endpoints / secrets: override in ~/.gradle/gradle.properties, never commit real keys.
        buildConfigField("String", "AGGREGATOR_URL", "\"https://melamur.snel.it/aggregator/\"")
        buildConfigField("String", "AGGREGATOR_PIN", "\"${project.findProperty("melamurPin") ?: ""}\"")
        buildConfigField("String", "CLIENT_P12_PASSWORD", "\"${project.findProperty("melamurP12Password") ?: "changeit"}\"")
        buildConfigField("String", "TALLINJA_BASE_URL", "\"${project.findProperty("tallinjaBaseUrl") ?: ""}\"")
        buildConfigField("String", "TALLINJA_API_KEY", "\"${project.findProperty("tallinjaApiKey") ?: ""}\"")
    }
    buildFeatures { buildConfig = true }
    androidResources { noCompress += listOf("bin") }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
