package id.hanifalfaqih.aienglishinterview.core.network

import id.hanifalfaqih.aienglishinterview.BuildConfig

/**
 * Backend base URL configuration.
 *
 * The URL lives in `BuildConfig.API_BASE_URL` (set per build type in
 * `app/build.gradle.kts`) so business logic never hardcodes a host:
 * - Emulator debug: `http://10.0.2.2:3001/` (`10.0.2.2` is the emulator
 *   alias for the dev machine's loopback; plain `localhost` would resolve
 *   to the emulator itself).
 * - Physical device: replace with the dev machine's LAN IP.
 * - Release: point at the deployed backend before shipping (currently the
 *   same dev placeholder).
 */
object ApiConfig {
    val baseUrl: String = BuildConfig.API_BASE_URL
}
