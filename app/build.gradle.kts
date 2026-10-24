plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * Release-signing values: a Gradle property (local builds, ~/.gradle/gradle.properties)
 * or an environment variable (CI, from GitHub secrets). Never commit them.
 * See docs/release-signing.md.
 */
fun signingValue(prop: String, env: String): String? =
    (findProperty(prop) as String?)?.takeIf { it.isNotBlank() } ?: System.getenv(env)?.takeIf { it.isNotBlank() }

val releaseKeystore = signingValue("melamurKeystore", "MELAMUR_KEYSTORE")

android {
    namespace = "it.snel.melamur"
    compileSdk = 35
    defaultConfig {
        applicationId = "it.snel.melamur"
        minSdk = 26
        targetSdk = 35
        // set by the release workflow from the tag / run number; defaults for local builds
        versionCode = (findProperty("melamurVersionCode") as String?)?.toInt() ?: 1
        versionName = (findProperty("melamurVersionName") as String?) ?: "0.1"
        // Endpoints / secrets: override in ~/.gradle/gradle.properties, never commit real keys.
        buildConfigField("String", "AGGREGATOR_URL", "\"https://melamur.snel.it/aggregator/\"")
        buildConfigField("String", "AGGREGATOR_PIN", "\"${project.findProperty("melamurPin") ?: ""}\"")
        buildConfigField("String", "CLIENT_P12_PASSWORD", "\"${project.findProperty("melamurP12Password") ?: "changeit"}\"")
        buildConfigField("String", "TALLINJA_BASE_URL", "\"${project.findProperty("tallinjaBaseUrl") ?: "https://api.meep.me"}\"")
        buildConfigField("String", "TALLINJA_ZONE", "\"${project.findProperty("tallinjaZone") ?: "Malta"}\"")
        // Your OWN application key from Malta Public Transport / Meep (see docs/). Empty => offline, bundled data only.
        buildConfigField("String", "TALLINJA_API_KEY", "\"${project.findProperty("tallinjaApiKey") ?: ""}\"")
    }
    signingConfigs {
        if (releaseKeystore != null) create("release") {
            storeFile = file(releaseKeystore)
            storePassword = signingValue("melamurKeystorePassword", "MELAMUR_KEYSTORE_PASSWORD")
            keyAlias = signingValue("melamurKeyAlias", "MELAMUR_KEY_ALIAS")
            keyPassword = signingValue("melamurKeyPassword", "MELAMUR_KEY_PASSWORD")
        }
    }
    buildTypes {
        // Debug builds are a separate app (it.snel.melamur.debug, "MelaMur debug"), so a
        // debug build never replaces a release and both can be installed side by side.
        // When the release key is available (CI on this repo) debug builds are signed with
        // it too: a stable key, so each new debug build installs over the previous one.
        getByName("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
        }
        getByName("release") {
            isMinifyEnabled = false   // no R8 until it has been tested on a device
            // without a keystore the release APK is simply left unsigned
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
        }
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
