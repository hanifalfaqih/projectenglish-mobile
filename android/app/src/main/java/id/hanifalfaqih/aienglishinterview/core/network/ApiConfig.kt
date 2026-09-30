package id.hanifalfaqih.aienglishinterview.core.network

import id.hanifalfaqih.aienglishinterview.BuildConfig

/**
 * Backend base URL configuration.
 *
 * The URL lives in `BuildConfig.API_BASE_URL` (set per build type in
 * `app/build.gradle.kts`) so business logic never hardcodes a host:
 * - Local debug (emulator AND physical device): `http://127.0.0.1:3001/`
 *   via `adb reverse tcp:3001 tcp:3001`, which forwards the
 *   device/emulator loopback to the Mac. `./gradlew devInstall` applies
 *   the reverse rule and installs the debug APK (canonical workflow).
 * - LAN-IP setups: override with `-PapiBaseUrl=http://<mac-lan-ip>:3001/`.
 * - Release: point at the deployed backend before shipping (currently a
 *   dev placeholder).
 */
object ApiConfig {
    val baseUrl: String = BuildConfig.API_BASE_URL
}
