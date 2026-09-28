plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "id.hanifalfaqih.aienglishinterview"
    compileSdk {
        version = release(37)
    }

    // Optional local override, e.g. in ~/.gradle/gradle.properties:
    //   revenueCatApiKey=test_...
    val revenueCatKey: String = providers.gradleProperty("revenueCatApiKey").getOrElse("")
    // Google Cloud Speech-to-Text API key (Android-restricted, see
    // docs/production-voice-setup.md). Supply via ~/.gradle/gradle.properties
    // as `googleCloudSpeechApiKey=AIza...` — never commit a key. Empty means
    // the recognizer reports unconfigured and makes no network requests.
    val googleCloudSpeechApiKey: String =
        providers.gradleProperty("googleCloudSpeechApiKey").getOrElse("")

    defaultConfig {
        applicationId = "id.hanifalfaqih.aienglishinterview"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // Emulator loopback to a backend running on the dev machine.
            // Physical device: use the machine's LAN IP instead.
            // Release: point at the deployed backend before shipping.
            buildConfigField("String", "API_BASE_URL", "\"http://10.0.2.2:3001/\"")
            // Public RevenueCat SDK key (Test Store key for debug). Supply via
            // ~/.gradle/gradle.properties as `revenueCatApiKey=test_...` — never
            // commit a key. Empty means the SDK stays unconfigured and the
            // paywall reports unavailable.
            buildConfigField("String", "REVENUECAT_API_KEY", "\"${revenueCatKey}\"")
            buildConfigField("String", "GOOGLE_CLOUD_SPEECH_API_KEY", "\"${googleCloudSpeechApiKey}\"")
        }
        release {
            buildConfigField("String", "API_BASE_URL", "\"http://10.0.2.2:3001/\"")
            // Release must use the Google Play public SDK key, same mechanism.
            buildConfigField("String", "REVENUECAT_API_KEY", "\"${revenueCatKey}\"")
            buildConfigField("String", "GOOGLE_CLOUD_SPEECH_API_KEY", "\"${googleCloudSpeechApiKey}\"")
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.kotlinx.serialization)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.revenuecat.purchases)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockito.core)
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}