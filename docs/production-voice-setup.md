# Production Voice Setup (Android)

This document describes how to configure the two production voice
providers. It is a setup runbook, not code: no credential lives in this
repository.

## Speech-to-text: Google Cloud Speech-to-Text (V1 synchronous REST)

1. Create (or reuse) a Google Cloud project and **enable the
   Speech-to-Text API** on it.
2. Create an **API key** (APIs & Services → Credentials → Create credentials
   → API key). This is a client key, not a service-account secret. Never
   create or embed service-account JSON for the Android app.
3. Restrict the key twice:
   - **API restriction**: Speech-to-Text API only.
   - **Android application restriction**: package
     `id.hanifalfaqih.aienglishinterview` plus the SHA-1 fingerprints of
     the debug certificate (local development) and, before any release,
     the release signing certificate.
4. Supply the key locally, never committed:
   `~/.gradle/gradle.properties` → `googleCloudSpeechApiKey=AIza…`
   (build reads it into `BuildConfig.GOOGLE_CLOUD_SPEECH_API_KEY`;
   empty default keeps the recognizer inert with a clear error).
5. In the Google Cloud console, set **quota/billing monitoring** (alerts on
   usage and spend) for the Speech-to-Text API.

### Security model (read carefully)

An Android API key is **not a secret**: anything in the APK can
theoretically be extracted. The protection is layered instead:

- Android application restriction (package + SHA-1) so the key is only
  usable from this app's signatures;
- API restriction so a leaked key cannot call other Google services;
- quota caps and billing alerts bounding abuse;
- no service-account credentials anywhere near the client.

Do not describe this key as a secret. Do not add service-account
credentials, backend token brokers, or key-proxy endpoints: the approved
architecture deliberately has no backend voice surface.

## Text-to-speech: Android framework TextToSpeech

No configuration, key, network, or dependency. The app requests the US
English voice at runtime; devices without an English voice report a clear
error through the normal voice-error path.

## Conservative development usage

Speech-to-Text V1 pricing (per official pricing at integration time)
includes a limited free allowance; verify current terms in the console
before heavy use. As a planning envelope for this project:

- one interview ≈ 5–8 answers × 30–60 s ≈ 3–8 audio minutes;
- ten development interviews ≈ 30–80 audio minutes;
- one demo recording ≈ 8 audio minutes.

Keep validation sessions tight, prefer short answers while iterating, and
watch the console usage graph. Never encode billing assumptions in app
logic; there is intentionally no local usage meter.
