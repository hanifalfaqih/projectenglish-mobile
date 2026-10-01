# Project English

> An AI-powered professional communication simulator that helps students practice communicating their real experiences through contextual conversations, feedback, and targeted practice.

Project English helps students turn their real experiences — projects, internships, competitions, research, and organizational activities — into realistic professional communication practice.

Instead of practicing only generic interview questions, users bring their own experiences into the conversation. The AI uses that context to ask relevant follow-up questions, respond to the user's answers, and provide feedback based on what they actually communicated.

---

## Core Experience

**Experience → Conversation → Contextual Follow-up → Feedback → Targeted Practice**

1. **Bring your experience**  
   Add an experience manually or import it from a resume.

2. **Practice through conversation**  
   Start an AI-powered professional interview based on the selected experience.

3. **Follow your story**  
   The AI asks contextual follow-up questions based on the experience and previous answers.

4. **Get feedback**  
   Receive qualitative feedback based on the actual conversation.

5. **Practice specific areas**  
   Use targeted practice to revisit eligible responses and work on areas identified in the feedback.

---

## Features

- Experience-based interview practice
- Resume-based experience import
- AI-powered contextual conversations
- Voice-based interview interaction
- Server-side speech recognition and text-to-speech
- Conversation-aware follow-up questions
- Qualitative post-interview feedback
- Detailed per-answer feedback for premium users
- Targeted practice for selected responses
- RevenueCat-powered premium entitlement and purchase flow

---

## Technology

### Android

- Kotlin
- Jetpack Compose
- Material 3
- Android Jetpack
- Retrofit
- Kotlin Coroutines
- RevenueCat SDK

### Backend

- Node.js
- TypeScript
- Fastify
- PostgreSQL
- Prisma
- Zod

### AI & Voice

- Alibaba Cloud Model Studio / DashScope
- Qwen models for conversation and feedback
- Qwen ASR for speech recognition
- Qwen TTS for assistant voice responses

---

## Architecture

```text
Android App
    │
    │ REST / multipart voice requests
    ▼
Fastify Backend
    │
    ├── Conversation
    ├── Feedback
    ├── Targeted Practice
    ├── Resume Processing
    └── Voice Processing
            │
            ├── Qwen ASR
            ├── Qwen LLM
            └── Qwen TTS
    │
    ▼
PostgreSQL
```

RevenueCat is integrated on the Android client to manage premium entitlements and the purchase experience.

---

## Project Structure

```text
.
├── android/     # Android application
├── backend/     # Fastify + TypeScript backend
├── docs/        # Architecture and implementation documentation
├── IDEA.md      # Original product idea
└── LICENSE      # MIT License
```

---

## Getting Started

### Prerequisites

- Android Studio
- JDK 11+
- Node.js
- PostgreSQL
- A configured Alibaba Cloud Model Studio / DashScope API key
- A RevenueCat Android API key for the configured project

### Backend

```bash
cd backend
npm install
npm run build
npm start
```

Configure the required backend environment variables before starting the server.

See the documentation in `docs/` for voice and production setup details.

### Android

Open the `android/` directory in Android Studio.

For local development, the project supports an API base URL override:

```bash
./gradlew assembleDebug -PapiBaseUrl=http://<backend-host>:3001/
```

For physical-device development using the local backend, the repository also provides the `devInstall` workflow described in the project documentation.

---

## Voice Architecture

Voice processing is handled by the backend.

```text
Android Microphone
       │
       ▼
Audio Capture
       │
       ▼
Backend /voice-turn
       │
       ├── Qwen ASR
       │
       ├── Conversation Pipeline
       │
       └── Qwen TTS
       │
       ▼
Android Audio Playback
```

The Android client does not contain Qwen credentials. Speech recognition and synthesis are performed server-side.

See [`docs/production-voice-setup.md`](docs/production-voice-setup.md) for more details.

---

## Testing

The repository contains Android and backend tests covering conversation, feedback, targeted practice, voice processing, resume processing, repositories, and related application logic.

Backend tests can be run with:

```bash
cd backend
npm test
```

---

## Demo

**Shipaton 2026 Demo**

https://youtu.be/fMk-AstXemw

---

## Built For

Project English was built for **Shipaton 2026**, with a focus on the **Next Gen Award**.

The project is designed primarily for students preparing for internships and early-career opportunities, especially students who have valuable technical experiences but need more practice communicating those experiences professionally.

---

## License

This project is licensed under the [MIT License](LICENSE).
