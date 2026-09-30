import java.io.File
import java.util.Properties
import org.gradle.api.GradleException

// Local-backend development workflow (physical device AND emulator).
//
// Deterministic topology: device/emulator 127.0.0.1:3001 ->
// `adb reverse tcp:3001 tcp:3001` -> Mac localhost:3001.
//
//   ./gradlew devInstall          (root alias, canonical)
//   ./gradlew :app:devInstall
//
// Applied by app/build.gradle.kts via `apply(from = ...)`. Deliberately NOT
// wired into assemble/install tasks: only this workflow touches ADB, so
// plain builds never depend on a connected device.

/** Local backend port forwarded from the device/emulator to this machine. */
val devBackendPort = 3001

/**
 * Android SDK location without AGP internals: local.properties `sdk.dir`
 * first, then ANDROID_HOME / ANDROID_SDK_ROOT, then PATH fallback.
 */
fun resolveAndroidSdkDir(): File? {
    val props = Properties()
    val localProps = rootProject.file("local.properties")
    if (localProps.isFile) {
        localProps.inputStream().use { props.load(it) }
        (props.getProperty("sdk.dir") as String?)?.let { return File(it) }
    }
    (System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT"))?.let {
        return File(it)
    }
    return null
}

val adbReverse = tasks.register("adbReverse") {
    group = "development"
    description = "Forwards device/emulator 127.0.0.1:$devBackendPort to this machine via 'adb reverse'. Fails if no device is connected."
    outputs.upToDateWhen { false }
    notCompatibleWithConfigurationCache("Runs adb against the connected device.")

    doLast {
        val sdkAdb = resolveAndroidSdkDir()?.resolve("platform-tools/adb")
        // Prefer the SDK-resolved adb; fall back to PATH ("adb"), which
        // fails with a clear message below when neither exists.
        val adb = if (sdkAdb != null && sdkAdb.isFile) sdkAdb.absolutePath else "adb"

        // Lambda (not a nested fun) so the surrounding scope stays visible.
        // Pure JDK ProcessBuilder: no Gradle exec API involved.
        val adbRun: (List<String>) -> String = { args ->
            val proc = try {
                ProcessBuilder(listOf(adb) + args).start()
            } catch (e: Exception) {
                throw GradleException(
                    "Cannot run adb ('$adb'): ${e.message}. " +
                        "Ensure Android platform-tools are installed and the SDK location is set.",
                )
            }
            val stdout = proc.inputStream.readBytes().toString(Charsets.UTF_8).trim()
            val exit = proc.waitFor()
            if (exit != 0) {
                val err = proc.errorStream.readBytes().toString(Charsets.UTF_8).trim()
                throw GradleException(
                    "adb ${args.joinToString(" ")} failed (exit $exit): $err",
                )
            }
            stdout
        }

        val requestedSerial = System.getenv("ANDROID_SERIAL")
        val attached = adbRun(listOf("devices")).lineSequence()
            .drop(1)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        if (attached.isEmpty()) {
            throw GradleException(
                "No Android device connected. Connect a device (USB or " +
                    "`adb connect <ip>` for wireless) and re-run.",
            )
        }
        val ready = attached.mapNotNull { line ->
            val parts = line.split(Regex("\\s+"))
            if (parts.size >= 2 && parts[1] == "device") parts[0] else null
        }
        val serial = when {
            requestedSerial != null -> {
                if (!ready.contains(requestedSerial)) {
                    throw GradleException(
                        "ANDROID_SERIAL=$requestedSerial is not connected and ready. " +
                            "Attached: $attached",
                    )
                }
                requestedSerial
            }
            ready.size == 1 -> ready[0]
            ready.isEmpty() -> throw GradleException(
                "Device attached but not ready (no 'device' state). " +
                    "Unlock it, accept the debugging authorization, then re-run. " +
                    "Attached: $attached",
            )
            else -> throw GradleException(
                "Multiple devices attached (${ready.joinToString()}). " +
                    "Set ANDROID_SERIAL to the target serial and re-run.",
            )
        }

        val target = listOf("-s", serial)
        adbRun(target + listOf("reverse", "tcp:$devBackendPort", "tcp:$devBackendPort"))
        val listing = adbRun(target + listOf("reverse", "--list"))
        if (!listing.contains("tcp:$devBackendPort")) {
            throw GradleException(
                "adb reverse did not take effect on $serial. " +
                    "Reverse list: '$listing'",
            )
        }
        logger.lifecycle(
            "adb reverse tcp:$devBackendPort established on $serial; " +
                "debug backend = http://127.0.0.1:$devBackendPort/",
        )
    }
}

tasks.register("devInstall") {
    group = "development"
    description = "Canonical local-backend workflow: adb reverse 3001, then install the debug APK. Fails if no device is connected."
    dependsOn(adbReverse, "installDebug")
    notCompatibleWithConfigurationCache("Runs adb against the connected device.")
}

// Reverse first so the freshly installed app can reach the backend
// immediately on launch. mustRunAfter only applies when both tasks are in
// the graph, so plain assemble/install builds never touch ADB.
// (installDebug is registered by AGP after evaluation, hence afterEvaluate.)
afterEvaluate {
    tasks.named("installDebug") { mustRunAfter(adbReverse) }
}
