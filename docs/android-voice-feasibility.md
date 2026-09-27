# Android Voice Feasibility

## 1. Objective

Determine whether Project English can reliably perform this spoken interview loop on a real Android device:

```text
Microphone
  -> Native Android speech recognition
  -> Recognized text
  -> Temporary interviewer response
  -> Native Android text-to-speech
  -> Speaker
```

This is a feasibility spike, not Android product implementation. It has two phases below: the earlier preflight (no code, no device) and the emulator implementation (spike built, installed, launched; human voice testing still pending). Nothing about STT recognition or TTS speech output is claimed until a human speaks into and listens to the emulator.

## 2. Product Requirement

The current Product Decision locks voice as a core product requirement. The minimum journey requires an experience-grounded spoken interview, contextual follow-up, completion, and professional communication feedback.

The user must hear the interviewer and speak their answer. Recognized speech can be submitted as text to the authoritative backend conversation engine. A text-only interview is not an acceptable minimum-journey fallback. If reliable real-device voice cannot be demonstrated, that is a product and technical blocker.

## 3. Environment

### Previous preflight (before the spike existed)

| Area | Observed environment | Status |
|---|---|---|
| Development OS | macOS | OBSERVED |
| Repository | No `android/` project, Gradle files, manifest, or Android dependencies | VERIFIED |
| Java | OpenJDK 25.0.2 | OBSERVED |
| Android SDK | `/Users/hanifalfaqih/Library/Android/sdk` | VERIFIED |
| Installed platforms | `android-36.1`, `android-37.0` | OBSERVED |
| Build tools | `36.0.0`, `36.1.0` | OBSERVED |
| SDK command paths | `adb` and `emulator` exist in the SDK but are not on shell `PATH` | OBSERVED |
| Emulator configuration | `Medium_Phone_API_36.1` AVD is configured | OBSERVED |
| Connected physical devices | None reported by `adb devices -l` | VERIFIED |
| Android project dependencies | None; no Android project exists | VERIFIED |
| Existing voice implementation | No Android implementation; source backend has no voice endpoints. Existing browser Web Speech code is explicitly out of scope. | VERIFIED |

### Emulator implementation (spike built September 28, 2026)

| Area | Observed environment | Status |
|---|---|---|
| Emulator in use | `emulator-5554`, model `sdk_gphone64_arm64`, Android 16 | VERIFIED |
| Android project | Minimal `android/` Gradle project, one `MainActivity`, no AndroidX, no third-party deps | VERIFIED |
| Toolchain | Gradle 9.5.0, AGP 9.3.1 with built-in Kotlin (no `kotlin("android")` plugin; applying it fails the build), compileSdk/targetSdk 36, minSdk 29 | VERIFIED |
| Platform install | Build auto-installed `platforms/android-36` (revision 2) from Google; license auto-accepted | OBSERVED |
| STT API used | `android.speech.SpeechRecognizer` + `RecognitionListener`, `RecognizerIntent.ACTION_RECOGNIZE_SPEECH`, `en-US`, partial results on | VERIFIED (code) / NOT VERIFIED (behavior) |
| TTS API used | `android.speech.tts.TextToSpeech`, `Locale.US`, `UtteranceProgressListener` driving listen-after-speak | VERIFIED (code) / NOT VERIFIED (speech output) |
| TTS init | Logcat shows `state=IDLE detail=ready` then `TTS ready (en-US)` after app launch | OBSERVED |
| Debug APK | `android/app/build/outputs/apk/debug/app-debug.apk` (2.5 MB), `BUILD SUCCESSFUL` | VERIFIED |
| Install | `adb -s emulator-5554 install -r` → `Success` | VERIFIED |
| Launch | `am start .../.MainActivity`, process running (pid observed), no crash in logcat | VERIFIED |
| Voice behavior | No human has spoken into or listened to the emulator yet | NOT VERIFIED |

## 4. Spike Architecture

The implemented minimal spike is:

```text
Microphone
  -> SpeechRecognizer (framework, no third-party SDK)
  -> Recognized text shown on a debug screen
  -> Deterministic local follow-up (rotates 3 hard-coded questions)
  -> TextToSpeech (framework, en-US)
  -> Speaker
```

Files: `android/app/src/main/java/com/projectenglish/voicespike/MainActivity.kt` (single activity, programmatic debug UI), `AndroidManifest.xml` (`RECORD_AUDIO` only), `app/build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, machine-local `local.properties`.

The temporary response exists only to validate the STT-to-TTS loop. It is not a production interviewer, backend integration, text fallback, or final speech architecture. No third-party speech provider, cloud/on-device decision, streaming protocol, or audio-upload architecture has been selected or tested.

The debug UI exposes Start interview / Stop-interrupt / Retry turn / Reset, the `IDLE` / `LISTENING` / `PROCESSING` / `SPEAKING` / `ERROR` state, recognized text (including partials), the interviewer response, and a 40-line event log. All voice events are also logged to logcat under tag `VoiceSpike` with millisecond timestamps around end-of-speech, results, and TTS start for approximate latency reading.

## 5. What Was Tested

### Preflight (no code)
- Current repository structure and Android-project presence.
- Current Git status.
- Java runtime availability.
- Android SDK location, installed platforms, build tools, and system images.
- ADB device discovery through the installed SDK platform tools.
- Emulator AVD discovery through the installed SDK emulator binary.
- Existing repository and backend-audit voice boundary.

### Emulator implementation (code, build, install, launch)
- Minimal Gradle project creation with AGP 9.3.1 built-in Kotlin.
- Debug build (`:app:assembleDebug` → `BUILD SUCCESSFUL`, `app-debug.apk`).
- Install on the already-running `emulator-5554` (`Success`).
- Launch via `am start`; process alive; logcat shows `IDLE/ready` and `TTS ready (en-US)`.

### Human voice testing (pending)
- The microphone, STT recognition, TTS speech output, listening/speaking state transitions, lifecycle, interruption, error recovery, latency, and interview-length answers are **NOT VERIFIED** until a human speaks into and listens to the emulator. APK installation and TTS engine init are not voice validation.

## 6. Results

| Area | Result | Evidence | Status |
|---|---|---|---|
| Microphone permission | Not tested | No Android app or physical device | NOT VERIFIED |
| Permission denial/recovery | Not tested | No Android app or physical device | NOT VERIFIED |
| English STT | Not tested | No Android app or physical device | NOT VERIFIED |
| Interview-length answer recognition | Not tested | No Android app or physical device | NOT VERIFIED |
| Indonesian-accented English | Not tested | No physical speaker/device test | NOT VERIFIED |
| End-of-speech and recognition errors | Not tested | No Android app or physical device | NOT VERIFIED |
| TTS initialization and intelligibility | Not tested | No Android app or physical device | NOT VERIFIED |
| TTS interruption/restart | Not tested | No Android app or physical device | NOT VERIFIED |
| `IDLE`/`LISTENING`/`PROCESSING`/`SPEAKING`/`ERROR` control | Not tested | No Android app | NOT VERIFIED |
| Lifecycle and focus interruption | Not tested | No Android app or physical device | NOT VERIFIED |
| Latency | Not measured | No voice loop executed | NOT VERIFIED |
| Emulator | `emulator-5554` running; spike installed, launched, TTS init observed; no voice turn executed | `adb devices -l`, install/launch/logcat output | VERIFIED (setup) / NOT VERIFIED (voice) |
| Physical Android device | No device attached | `adb devices -l` returned no devices | VERIFIED |

### Manual test round 1 + diagnosis (September 28, 2026)

The developer spoke into the emulator; every turn ended in `STT error code=7`
(`ERROR_NO_MATCH`) with no recognized text. The on-screen loop
(`startListening` → `STT ready` → `STT speech began` → `STT end-of-speech` →
`startListening`…) is repeated human **Retry** presses, not an app auto-restart:
`onError()` and `onEndOfSpeech()` never call `startListening()` (verified by code
read), and `turn` stays 0 because it only increments in `onResults()`.

Service-side evidence (logcat, recognition-service process) for the same session:

- Bound service is `com.google.android.tts/...GoogleTTSRecognitionService`
  (the system default; a second provider,
  `com.google.android.as/...AiAiSpeechRecognitionService`, is also installed).
- Its offline SODA recognizer logged `start detection` → `stop detection`
  within ~2ms, then `Audio process finished` and
  `#onRecognitionFinished no speech - erroring` (`NO_SPEECH_DETECTED`).
- Emulator has working IP networking (ping to 8.8.8.8 succeeds), so this is not
  a connectivity outage; the offline path was attempted and heard silence.

Interpretation: error 7 (`SpeechRecognizer.ERROR_NO_MATCH == 7`, Android API
constant) here means the recognizer processed effectively empty audio. The
microphone path — host mic → emulator virtual mic → recognition service — is
delivering silence despite host-mic access being ON in Extended Controls.
Remaining mic-path suspects: macOS Microphone permission for the emulator
process, wrong host input device/level, or the virtual mic not capturing.

One clear app bug was also found and fixed: `EXTRA_LANGUAGE` was set to
`Locale.US.toString()` (`"en_US"`, underscore) instead of the BCP-47 tag
`"en-US"`. The fixed build is installed and launches cleanly
(`state=IDLE detail=ready`). STT behavior after the fix is NOT VERIFIED —
the language tag is unlikely to be the primary cause given the service-side
no-speech evidence, so the next test must isolate the mic path first.

## 7. Speech Recognition Observations

No recognition result was produced. Therefore there is no evidence about short, medium, long, technical, paused, hesitant, or Indonesian-accented English answers; no transcription examples; and no basis to judge whether recognized text is usable as authoritative conversation input.

## 8. TTS Observations

No TTS engine was initialized or used. Intelligibility, English locale behavior, startup delay, voice quality, stopping, restarting, and audio-focus behavior are all **NOT VERIFIED**.

## 9. Failure and Recovery Observations

No application-level failure scenario was run. Permission denial, no-speech, recognition timeout/error, TTS initialization failure, TTS interruption, lifecycle interruption, and retry after failure remain **NOT VERIFIED**.

## 10. Real Device Assessment

### Emulator

An AVD named `Medium_Phone_API_36.1` is configured. It was not launched and does not establish microphone, recognition, TTS, speaker, audio-focus, or interruption behavior for a real device.

### Physical device

No physical Android device was reported by the installed ADB tool. The required real-device validation cannot proceed in the current environment.

## 11. Feasibility Conclusion

**NOT YET VERIFIED** — first human round produced consistent `ERROR_NO_MATCH`
with service-side "no speech detected"; cause narrowed to the mic audio path.

The native loop is implemented, installed, and launchable, and TTS initializes
to en-US, but no spoken turn has been recognized yet. Native STT is not
disproven: the app state machine is sound (no auto-restart bug), a recognition
service is present, networking works, and the failure signature is empty audio,
not a broken API. The decision now hinges on the mic-path isolation tests
below. Physical-device validation remains a separate unresolved risk.

## 12. Remaining Risks

- Real-device microphone permission and recovery behavior.
- Recognition accuracy and end-of-speech behavior for realistic professional-English answers.
- Recognition behavior for Indonesian-accented English, natural pauses, hesitation, and technical terms.
- TTS English voice availability, intelligibility, audio focus, stopping, and restart behavior.
- Voice-loop state control, interruption handling, lifecycle behavior, and retry recovery.
- End-to-end latency from final speech to recognition result to spoken follow-up.
- Device and Android-version compatibility.

## 13. Production Decisions Still Open

This spike does not decide:

- Production Android STT provider or API.
- Production Android TTS provider or API.
- Cloud versus on-device speech processing.
- Streaming, WebSocket, audio-upload, or backend audio architecture.
- Backend audio storage or server-side speech processing.
- Final voice interaction UX, error UX, or latency thresholds.
- Backend snapshot, authentication, RevenueCat, or production persistence architecture.

## 14. Recommended Next Step

### Immediate: mic-path isolation tests (developer; fixed build already installed)
1. macOS check: System Settings → Privacy & Security → Microphone → confirm the
   emulator (Android Studio / qemu) is allowed. Also confirm the intended host
   input device is selected and its input level moves when you speak.
2. System isolation: open the **Google app** on the emulator, tap its
   microphone, and speak. If it also fails/returns nothing, the cause is the
   system mic path, not our app. If it transcribes, the cause is app-side.
3. Retest VoiceSpike: **Reset**, then **Start interview**, speak loudly and
   continuously for several seconds per turn. Report exact on-screen text
   (error names are now shown, e.g. `STT ERROR_NO_MATCH (7)`), one background /
   foreground cycle, and **Stop / interrupt** mid-speech.
4. Collect and send back: full `adb -s emulator-5554 logcat -d` output covering
   the test (both `VoiceSpike` and recognition-service lines), plus the Google
   app voice-search result.
5. Only with recognized text in hand can Section 11 move from NOT YET VERIFIED.

### After emulator evidence
Connect a physical Android device with USB debugging authorized and repeat the same turns there before any production Android or backend decision. Physical-device behavior stays NOT VERIFIED until that happens.
