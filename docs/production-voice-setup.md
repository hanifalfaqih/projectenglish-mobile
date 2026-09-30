# Production Voice Setup (Android + Backend)

This document describes the production voice architecture. It is a setup
runbook, not code: no credential lives in this repository or the APK.

## Architecture

```
Android microphone (AudioRecord, 16 kHz mono 16-bit PCM)
  → POST /conversations/:id/voice-turn (multipart `audio` + `clientTurnId`)
  → backend: Qwen3-ASR-Flash transcript
  → backend: existing conversation turn pipeline (transcript = message)
  → backend: Qwen TTS synthesis of assistantMessage
  → response: transcript + assistant/state/status/closing + PCM audio
  → Android AudioTrack playback
```

Speech recognition AND synthesis run server-side. The Android client never
holds Qwen credentials and performs no STT/TTS networking of its own.

## Speech-to-text: Qwen-Audio-3.0-ASR-Flash (backend)

Model `qwen-audio-3.0-asr-flash` over the native DashScope
multimodal-generation endpoint
(`{ASR_BASE_URL}/api/v1/services/aigc/multimodal-generation/generation`,
default shared domain `https://dashscope-intl.aliyuncs.com`, overridable
per workspace/region). Request: WAV base64 data URI, `format: wav`,
`sample_rate: "16000"`, `language_hints: ["en"]`, `X-DashScope-SSE:
disable`. Response transcript at `output.text` (fallback
`output.sentence.text`); blank means no-speech (HTTP 422, no turn).

## Speech-to-speech output: Qwen TTS (backend)

- Model: `qwen-audio-3.0-tts-flash` (override: `TTS_MODEL_ID`)
- Voice: `loongjohn` — calm, friendly American English male (override:
  `TTS_VOICE_ID`)
- Protocol: Model Studio WebSocket, one session per utterance
  (`TTS_WS_URL`, default
  `wss://dashscope-intl.aliyuncs.com/api-ws/v1/inference`)
- Output: PCM 16-bit mono 24 kHz (override timeout: `TTS_TIMEOUT_MS`)
- TTS runs strictly AFTER the conversation turn commits. Synthesis
  failure degrades only the audio payload (`audio: null` + `audioError`);
  the turn is never rolled back.

## Backend environment

| Variable | Purpose |
|---|---|
| `DASHSCOPE_API_KEY` | Server-side Qwen key for ASR, LLM, and TTS (required) |
| `ASR_MODEL_ID` | Default `qwen3-asr-flash` |
| `TTS_MODEL_ID` | Default `qwen-audio-3.0-tts-flash` |
| `TTS_VOICE_ID` | Default `loongjohn` |
| `TTS_WS_URL` | Default intl inference endpoint above |
| `TTS_TIMEOUT_MS` | Default 60000 |

## Android playback

`BackendAudioPlayer` plays the response PCM via AudioTrack (24 kHz, mono,
16-bit; validated before playback). If `audio` is absent, the app falls
back to on-device TextToSpeech and shows a recoverable notice. The
platform synthesizer remains as fallback/dev diagnostic.

## Token Plan / credential caveat

Qwen credentials are backend-only and never ship in the app. Token Plan
documentation treats its keys/endpoints separately from backend service
usage: if the TTS WebSocket handshake rejects our credentials (401/403),
that is an account/service-compatibility matter, not an implementation
bug — preserve the integration and switch configuration. Do not assume
any fixed mapping between Token Plan credits and ASR/TTS requests;
verify usage in the console.

## Physical-device testing

- Canonical workflow: `./gradlew devInstall` (from `android/`). It applies
  `adb reverse tcp:3001 tcp:3001` and installs the debug APK, so the app
  reaches the Mac backend at `http://127.0.0.1:3001/` on emulator and
  physical device alike. Fails clearly when no device is connected.
- Alternative LAN-IP setup: build with `-PapiBaseUrl=<lan-url>/`.
- Tap mic → speak → stop → transcript uploads → spoken reply plays.
- Microphone permission is requested on first mic tap only.
