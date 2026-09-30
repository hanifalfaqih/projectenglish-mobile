// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// Canonical local-backend workflow alias (implementation: :app:devInstall).
// Forwards device/emulator 127.0.0.1:3001 to Mac localhost:3001 via
// `adb reverse`, then installs the debug APK.
tasks.register("devInstall") {
    group = "development"
    description = "Canonical local-backend workflow: adb reverse 3001 + install debug APK (see :app:devInstall)."
    dependsOn(":app:devInstall")
}