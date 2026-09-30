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

    // Public RevenueCat SDK key, supplied externally (e.g. in
    // ~/.gradle/gradle.properties as `revenueCatApiKey=test_...`). Never
    // commit a key; empty leaves the SDK unconfigured and the paywall
    // reports unavailable.
    val revenueCatKey: String = providers.gradleProperty("revenueCatApiKey").getOrElse("")
    // Debug uses the dev-machine loopback so one topology serves emulator
    // and physical devices alike (device 127.0.0.1:3001 -> `adb reverse`
    // -> Mac localhost:3001; `./gradlew devInstall` sets this up).
    // Override for LAN-IP setups: -PapiBaseUrl=http://<mac-lan-ip>:3001/
    // or apiBaseUrl in ~/.gradle/gradle.properties. No machine IPs in source.
    val debugApiBaseUrl: String =
        providers.gradleProperty("apiBaseUrl").getOrElse("http://127.0.0.1:3001/")
    // WARNING: historical dev placeholder, not a production endpoint
    // (10.0.2.2 is the emulator loopback). Point release at the deployed
    // backend before shipping.
    val releaseApiBaseUrl: String =
        providers.gradleProperty("apiBaseUrl").getOrElse("http://10.0.2.2:3001/")

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
            buildConfigField("String", "API_BASE_URL", "\"${debugApiBaseUrl}\"")
            // Test Store key for debug; release uses the Play public key.
            buildConfigField("String", "REVENUECAT_API_KEY", "\"${revenueCatKey}\"")
        }
        release {
            buildConfigField("String", "API_BASE_URL", "\"${releaseApiBaseUrl}\"")
            buildConfigField("String", "REVENUECAT_API_KEY", "\"${revenueCatKey}\"")
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

// Local development workflow (adb reverse + install); implementation lives
// in gradle/dev-install.gradle.kts to keep this file declarative.
apply(from = rootProject.file("gradle/dev-install.gradle.kts"))