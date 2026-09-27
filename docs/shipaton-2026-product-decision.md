# Shipaton 2026 — Product Decision

## 1. Purpose and Context

This repository is the dedicated delivery repository for a RevenueCat Shipaton 2026 Next Gen Award submission. It is separate from the main Project English development repository and is not a continuation milestone of that repository's roadmap.

This is a competition delivery track: it will contain the native Android submission, a later self-contained backend snapshot, public-source documentation, and the assets needed to submit and evaluate the project. Work in the main Project English repository is outside this repository's scope.

## 2. Competition Constraints

### Mandatory requirements

- The Next Gen entrant must be an active student in high school, college, university, bootcamp, or another academic program and use a qualifying student or academic Devpost email. The email domain may be verified through JetBrains/swot.
- Entrants aged 13 through below the local age of majority may enter only the Next Gen Award, with parent or legal guardian agreement and the required consent process.
- The project must be a working app for an eligible platform. Android is an eligible platform.
- The app must use the RevenueCat SDK for at least one in-app or web purchase, or serve ads through RevenueCat Ads.
- The app must install and run consistently on its intended platform and function as depicted in its video and text description.
- The project must be accessible from the United States.
- Third-party SDKs, APIs, and data may be used only when authorized and in compliance with their applicable terms and licenses.
- The submission must be original, solely owned by the entrant, team, or organization, and must not infringe intellectual-property, contract, privacy, or other rights. Open-source components require compliance with their licenses and the submission must enhance the underlying open-source product.
- The project must not have been developed with, derived from, or received financial or preferential support from RevenueCat or Devpost before the end of the Submission Period if that support would make it ineligible under the Rules.
- Submission materials and content must not contain harmful or malicious code.

### Submission requirements

- Submit during the Submission Period: July 31, 2026, 8:00am PDT through September 30, 2026, 11:45pm PDT. Registration also closes September 30, 2026, 11:45pm PDT. Dates may change at the Sponsor's discretion; the Hackathon Website is authoritative.
- Submit through Devpost with a text description explaining the app's features and functionality.
- For Next Gen, provide a public code-repository URL for judging and testing. It must include all source code, assets, and instructions necessary to make the project functional.
- The repository must be open source, include an open-source license file, and make that license detectable and visible at the top of the repository page, in the About section.
- Provide a public YouTube or Vimeo demonstration-video URL. The video should be under two minutes, show the app functioning on its intended device, and use third-party marks, music, and other material only with permission.
- Provide a 1024 x 1024 app icon.
- Provide at least one 1179 x 2556 pixel app screenshot without a device frame.
- Submission materials must be in English, or include English translations of the demonstration video, text description, testing instructions, and all other submitted materials.
- After the Submission Period, submission materials cannot be changed except as the Sponsor and Devpost permit for limited remediation.

### Next Gen exceptions and evaluation

- Next Gen does not require a paid Apple or Google developer account, a store release, a free trial, a promo code, or store-download testing. It is evaluated from the demonstration video and public code repository.
- The normal first-public-store-release timing rule does not apply to the Next Gen alternative of a demo video and public repository.
- Judges consider whether the idea is clear, useful, interesting, or original; whether video and code demonstrate meaningful progress toward a working app; whether RevenueCat is used thoughtfully for a monetization flow; and whether technical choices, product thinking, and presentation show care.
- Judges are not required to test a project and may judge from submitted text, images, and video. The repository must nevertheless meet the explicit completeness requirement.

### Optional category requirements

- No additional prize category is selected by this decision. Their requirements are not in scope unless deliberately chosen later.
- In particular, Ship Kotlin Everywhere would require both iOS and Android with Kotlin Multiplatform and/or Compose Multiplatform, live App Store and Google Play URLs, and additional public-build materials. It is incompatible with this repository's Android-only direction unless that direction is explicitly changed.
- Categories such as #BuildInPublic, HAMM, Design, Peace, Catvertising, Samsung Galaxy, OneSignal, Layers, Replit, Stripe, Noise, and influencer awards have category-specific requirements only if entered.

## 3. Submission Objective

Produce a compliant Next Gen Award submission: a native Kotlin Android app for a coherent professional-English practice experience, with a thoughtful RevenueCat monetization integration, public and complete open-source source code, required assets, and a concise device demonstration video.

The target submission is the Devpost project, its public repository, and its required media and written materials. A public app-store listing is not an objective or compliance requirement for this Next Gen submission.

## 4. Product Definition

The product is a native Android client for the Project English professional-English practice experience. Kotlin is the chosen native Android language.

The product recreates a realistic English-language professional or case-interview interaction: the user's own experience provides meaningful context, the AI interviewer conducts a spoken multi-turn interview with contextual follow-up, and the completed session produces professional communication feedback. It is not generic English conversation practice, a text chatbot, or a generic interview-question generator.

The locked product behavior does not establish detailed feedback scoring, user identity, or exact session mechanics beyond the existing backend contract. Those remain **DEPENDENT ON EXISTING BACKEND** or **NEEDS DECISION** where noted below.

## 5. Target User and Problem

Confirmed direction: the app supports people practicing how to communicate their own professional or academic experience verbally in an English-language interview.

- Intended user: **NEEDS DECISION**. Available context does not define a profession, proficiency level, geography, learning setting, or whether users are individuals or organization members.
- User problem: the user needs realistic spoken practice explaining, elaborating on, clarifying, and responding spontaneously about their experience in professional English. The specific audience, proficiency level, geography, and success measures remain **NEEDS INPUT**.
- Locked product value: realistic spoken practice, experience-grounded questioning, contextual follow-up, and actionable professional communication feedback.

## 6. Core User Experience

### Locked minimum mobile journey

1. **My Experience:** the user provides or selects a real professional or academic experience as interview context. The existing backend supports experience-profile creation and conversation association; exact Android fields remain **DEPENDENT ON EXISTING BACKEND**.
2. **Start Interview:** the user starts a practice interview based on that selected experience.
3. **Experience-Grounded Voice Interview:** the AI interviewer speaks a question, the user answers verbally, and the client converts the spoken answer into usable text for the authoritative conversation engine. The backend persists and advances the multi-turn conversation state.
4. **Contextual Follow-up Questions:** the interviewer follows the preceding answer and/or experience context to create a realistic, spontaneous interview interaction rather than a fixed generic question list. The existing backend supports contextual conversation state and experience context; it is **NOT YET VERIFIED** that every individual question will be dynamically grounded in experience.
5. **Interview Completion:** the session reaches the existing backend closed state.
6. **Professional Communication Feedback:** after completion, the user receives the existing backend's professional communication feedback as an actionable session outcome. Exact presentation and scoring detail remain **DEPENDENT ON EXISTING BACKEND**.

### Voice requirement

- Voice is a **CORE PRODUCT REQUIREMENT**, not an optional interaction layer. The user must hear the interviewer, speak an answer, have it recognized as usable conversation input, receive the next spoken response or question, and continue to completion and feedback.
- Voice is a core client capability layered over the authoritative text-based conversation engine. The backend continues to receive recognized text and remains authoritative for conversation state and AI logic.
- A text-only interview does not satisfy the minimum Shipaton journey. A text input may exist for development or debugging, but must not be presented as the user-facing minimum journey.
- If voice cannot be demonstrated reliably on a real device, it is a product and technical blocker. The product must not silently be redefined as a text interview.
- RevenueCat remains part of the product because the Rules require its use for a monetization flow. Its model is not selected.

### Core and stretch scope

- **CORE / REQUIRED:** My Experience; experience-grounded context; voice-based multi-turn interview; contextual follow-up; completion; professional communication feedback; persisted conversation state; and recovery from normal client/network interruption where supported by the backend.
- **STRETCH:** retrying a specific answer, retry feedback, and an additional review experience.
- **FUTURE:** durable learning, cross-session learning progression, more advanced personalization, and additional practice modes.

### Unresolved decisions

- Exact experience-input fields, question sequencing, detailed feedback presentation, progress model, and success criteria: **DEPENDENT ON EXISTING BACKEND** and **NEEDS DECISION**.
- Authentication and account model: **DEPENDENT ON EXISTING BACKEND**.
- Which monetized capability is meaningful and available: **NEEDS DECISION**.

## 7. Android Application Scope

The native Kotlin application will be responsible for:

- Android UI, navigation, and user interaction for the selected practice journey.
- Communicating with the self-contained backend snapshot through its defined API.
- Clear loading, empty, unavailable, and recoverable error states for network-dependent work.
- Microphone permission handling, spoken-input recognition, and spoken interviewer output as required client capabilities for the locked voice journey.
- RevenueCat SDK integration for the selected required monetization flow and corresponding access state in the user experience.
- Presenting only product behavior that can be supported by the imported backend and demonstrated reliably on a device.

The source backend has no server-side STT, TTS, audio upload, audio storage, or voice-specific endpoint. Existing web voice uses the browser Web Speech API and must not be copied into the Android architecture. Native Android speech recognition, speech output, microphone/audio permissions, compatibility, interruption handling, failure UX, latency, and audio architecture are **NEEDS DECISION**. No speech API, library, third-party provider, streaming protocol, WebSocket, or audio-upload design is selected here.

## 8. Backend Strategy

- Reuse the existing Project English backend rather than rebuilding it.
- Later copy only the required backend implementation into `backend/` as a self-contained submission snapshot.
- Treat the copied backend as the submission's documented snapshot, not a second ongoing source of truth.
- Document the snapshot origin, included revision or version, setup steps, environment variables, dependencies, and known limits when it is imported.
- Ensure the public repository has everything needed for the project to be functional, consistent with the Next Gen repository requirement.

The backend audit establishes the minimum required backend surface for the locked journey:

- Experience profile creation.
- Conversation creation, multi-turn conversation turns, contextual state, and recovery.
- Interview completion/closed state.
- Feedback generation and retrieval after completion.
- Health endpoint.
- Qwen-compatible conversation and feedback providers.
- PostgreSQL/Prisma persistence and a reproducible migration baseline.

Retry, retry feedback, review, learning derivation, learning retrieval, browser Web Speech implementation, and voice backend/audio infrastructure are not required for the minimum journey. Voice remains required at the product level, but the existing text-based conversation contract can receive recognized text from the native client.

Authentication, deployment/runtime model, data ownership, content ownership, test fixtures, secret replacement, OSS release authority, and a reproducible migration baseline remain **DEPENDENT ON EXISTING BACKEND**, **NEEDS INPUT**, or **NOT YET VERIFIED** as documented in the backend audit.

## 9. RevenueCat Strategy

RevenueCat use is mandatory: the Rules require the RevenueCat SDK to power at least one in-app or web purchase, or RevenueCat Ads.

Possible approaches are a subscription, a one-time in-app purchase, a web purchase, or RevenueCat Ads. No model is selected yet. The chosen model must fit the verified product journey, be visible in the code and demonstration, and be described clearly enough for Next Gen judges to assess its thoughtfulness.

Before implementation, decide:

- The specific paid, unlocked, or ad-supported user value.
- Whether the Android app will use a purchase flow or RevenueCat Ads.
- Product identifiers, entitlement or access behavior, testing configuration, and fallback behavior.
- How the backend and client recognize access, if backend involvement is required.

Do not build multiple paywalls, pricing experiments, a web funnel, ads, or additional revenue streams merely for complexity. Those are not required for Next Gen compliance.

## 10. Repository Architecture

The intended final structure is:

```text
project-aienglish-mobile/
├── android/          # Native Kotlin Android application
├── backend/          # Self-contained Project English backend submission snapshot
├── docs/             # Product, setup, compliance, and delivery documentation
├── README.md         # Public project overview and runnable setup path
├── LICENSE           # Repository open-source license
└── .env.example      # Documented variable names and safe example values only
```

- `android/` contains the Android client and its build, test, and setup instructions.
- `backend/` contains the later imported backend snapshot and its reproducible local or hosted-run instructions.
- `docs/` contains the product decision, architecture decisions, verification evidence, asset checklist, and compliance record.
- `README.md` is the public entry point and must explain prerequisites, configuration, how to run the project, how to test the demonstrated path, and limitations.
- `LICENSE` satisfies the Next Gen open-source-license requirement and must be reflected visibly in the repository About section.
- `.env.example` documents required configuration without containing credentials.

## 11. Open Source and Security Strategy

The eventual public repository must contain no secrets, API keys, database credentials, personal data, or proprietary assets without explicit rights to publish them. This includes source files, configuration, test fixtures, screenshots, demo material, generated artifacts, repository metadata, and Git history.

Before public release:

- Select a compatible open-source license, add `LICENSE`, and expose it in the repository About section.
- Review dependencies, copied backend components, assets, sample content, and third-party integrations for licensing and authorization.
- Replace secrets with environment variables and document variable names, purpose, required/optional status, and safe examples in `.env.example` and setup instructions.
- Review current and imported Git history for credentials, personal data, proprietary material, and unrelated history before publication.
- Use synthetic, authorized, or appropriately licensed demo data and media.
- Verify README setup instructions enable a reviewer to understand and run the functional project without undisclosed private source.

No security or open-source review has been performed yet: **NOT YET VERIFIED**.

## 12. Device Verification Strategy

Before submission, verify on at least one real Android device:

- Installation and first launch.
- The selected end-to-end practice journey.
- Backend connectivity from the device, including failure and recovery states.
- RevenueCat initialization and the selected purchase or Ads behavior, including user-visible failure handling.
- Microphone permission, voice input recognition, and spoken interviewer output across the locked journey.
- Speech-recognition failure, interruption, and recovery behavior without redefining the user journey as text-only.
- App behavior after interruption, relaunch, and relevant persisted-access or session recovery states.
- That the app behavior matches the video, screenshots, and text description.
- That the app can be demonstrated consistently for the public video.

None of these checks has occurred: **NOT YET VERIFIED**. Device models, Android-version coverage, microphone/audio-permission behavior, speech compatibility, speech latency, test accounts, and test-versus-production RevenueCat configuration are **NEEDS DECISION**.

## 13. Submission Assets

Required Next Gen submission assets are:

- A Devpost text description explaining features and functionality.
- A public YouTube or Vimeo demonstration video, under two minutes where possible, showing the Android app functioning on a device.
- A 1024 x 1024 app icon.
- At least one 1179 x 2556 pixel screenshot without a device frame.
- A public repository URL with source code, assets, instructions, and an open-source `LICENSE` visible in the repository About section.
- English materials or complete English translations, including testing instructions.

The final `README.md` must serve as setup and testing instructions. The final submission description, icon, screenshot, video, and README are **NOT YET CREATED**.

## 14. Explicit Non-Goals

Unless required for compliance or the selected end-to-end experience, this repository will not:

- Rebuild the existing Project English backend.
- Redesign the main Project English architecture.
- Rebuild the web application.
- Treat text-only interview as a minimum-journey fallback.
- Add unrelated AI or product features.
- Build iOS support.
- Use Kotlin Multiplatform solely for a category that is not selected.
- Add unnecessary infrastructure changes.
- Pursue store publication solely for Next Gen compliance.

## 15. Acceptance Criteria

### Product functionality

- The user can provide or select an experience and start a practice interview based on it.
- The AI interviewer speaks to the user; the user responds by speaking; the spoken response becomes usable conversation input.
- The interview continues through contextual follow-up, reaches completion, and produces professional communication feedback.
- All demonstrated behavior is supported by documented product and backend behavior.

### Voice

- The minimum experience is not accepted if it is text-only, requires the user to type answers to complete the intended journey, or uses voice only as an optional demo gimmick.
- Voice interaction is reliable enough to complete a coherent real-device interview for the demonstration.

### Android application

- A native Kotlin Android app installs, launches, and consistently runs on its intended Android device.
- It presents usable loading and error states for its selected user journey.
- Its actual behavior matches the submitted video and text description.

### Backend integration

- The repository includes the required backend snapshot, configuration guidance, source, assets, and instructions needed for functional use.
- The Android app completes its selected journey against the documented backend setup.
- Conversation state remains server-authoritative; recognized speech is submitted through the existing conversation-turn contract; persisted state supports recovery; and feedback can be generated after completion.

### RevenueCat

- The app uses the RevenueCat SDK for at least one in-app or web purchase, or RevenueCat Ads.
- The implementation supports a meaningful, documented monetization or access flow rather than an unused integration.

### Real-device verification

- The device checks in Section 12 are completed and recorded before the demo is captured.
- Voice-specific checks are completed because voice is in scope.

### Repository completeness

- The public repository contains all necessary source code, assets, and instructions required for the project to be functional.
- The repository URL, `LICENSE`, README, and repository About configuration meet the Next Gen repository requirement.

### Open-source readiness

- No secrets, credentials, personal data, unlicensed assets, or unauthorized third-party code are present in the published tree or relevant history.
- Dependencies and copied components have been reviewed for license and usage compliance.

### Submission assets

- The Devpost description, video URL, icon, screenshot, repository URL, and English materials are supplied by the deadline.
- The video is public, shows the app on its intended device, and is no longer than two minutes for full judge review.
- The video visibly demonstrates a spoken interview experience rather than a chatbot UI with typed responses.

## 16. Risks

- The September 30, 2026, 11:45pm PDT deadline leaves limited time for integration, real-device evidence, public-source review, media production, and Devpost submission.
- The core experience and technical dependencies cannot be fully scoped until the existing backend snapshot is imported.
- The locked voice journey adds microphone permission, speech recognition/output, audio-routing, interruption, latency, and device-reliability risk. A reliable spoken demo is mandatory; text-only fallback is not acceptable.
- RevenueCat configuration and the choice of a meaningful monetization flow could delay a compliant, demonstrable integration.
- A copied snapshot can drift from its source, omit dependencies, or create uncertainty about ownership and support.
- Publishing a complete repository increases the risk of exposing credentials, personal data, proprietary content, or third-party license violations.
- The video must reliably show real Android behavior in under two minutes; demo-only paths that differ from the repository reduce credibility and violate the consistency requirement.
- Eligibility, academic-email verification, minor consent where relevant, missing repository-About license visibility, or incomplete English materials can create compliance risk.

## 17. Delivery Sequence

1. Product Decision: retain this decision record and confirm Next Gen eligibility and student-email readiness.
2. Voice feasibility and snapshot preparation: determine whether native Android can reliably provide spoken input and output; resolve the reproducible migration and OSS-release blockers before copying any backend.
3. Android repository foundation: create the native Kotlin project, basic documentation, and safe configuration conventions.
4. Backend snapshot import: only after the blockers are resolved, copy and document the minimal required backend snapshot and audit its selected runtime dependencies.
5. API contract and integration: establish the actual client/backend path based on imported capabilities.
6. Core Android experience: implement the locked experience-grounded spoken interview, contextual follow-up, completion, and feedback journey with loading and error handling.
7. RevenueCat integration: select and implement one meaningful compliant monetization flow early enough for device testing.
8. Real-device verification: validate the working journey, integrations, recovery behavior, and video path.
9. Open-source hardening: complete secret, history, dependency, asset, license, and repository-completeness reviews.
10. Submission assets: capture icon, frame-free screenshot, public video, English description, and runnable README instructions.
11. Final compliance audit: validate every requirement in Section 2 against the actual Devpost draft and public repository.
12. Submission: submit before the published deadline; do not rely on post-deadline edits.

## 18. Final Decisions and Open Decisions

### Locked Decisions

- This is a separate Shipaton 2026 delivery repository, not a main Project English roadmap milestone.
- The target category is RevenueCat Shipaton 2026 Next Gen Award.
- The client will be a native Kotlin Android application.
- The app will provide experience-grounded professional-English interview practice, not generic conversation practice.
- The minimum journey is My Experience, Start Interview, Experience-Grounded Voice Interview, Contextual Follow-up Questions, Interview Completion, and Professional Communication Feedback.
- Voice is a core product requirement. Text-only interview is not an acceptable fallback for satisfying the minimum journey.
- The existing Project English backend will be reused later as a self-contained snapshot in this repository; it will not be rebuilt here.
- The minimum backend surface includes experience profile creation, conversation creation/turns/recovery, completion, feedback generation/retrieval, health, Qwen-compatible providers, PostgreSQL/Prisma persistence, and a reproducible migration baseline.
- Retry, retry feedback, review, learning, browser Web Speech, and voice-backend/audio infrastructure are outside the minimum backend surface.
- RevenueCat will be integrated.
- The final repository is intended to be self-contained, public, and open source with a license.
- iOS, Kotlin Multiplatform, web-app reconstruction, unrelated features, and unnecessary infrastructure are out of scope.

### Open Decisions

- Active-student eligibility, qualifying academic Devpost email, and any required minor-consent process: **NEEDS INPUT**.
- Exact target user, proficiency level, geography, and success measures: **NEEDS INPUT**.
- Exact experience-input fields, question sequencing, feedback presentation, identity, and persistence behavior: **DEPENDENT ON EXISTING BACKEND** and **NEEDS DECISION**.
- Native Android speech recognition and speech-output implementation, device compatibility matrix, microphone/audio permission behavior, interruption/recovery behavior, speech-recognition failure UX, and speech latency: **NEEDS DECISION**.
- Backend APIs, deployment, dependencies, configuration, security model, ownership, license implications, and migration snapshot strategy: **DEPENDENT ON EXISTING BACKEND**, **NEEDS INPUT**, or **NOT YET VERIFIED**.
- RevenueCat purchase-versus-Ads model and its user value: **NEEDS DECISION**.
- License selection and final public repository host/About configuration: **NEEDS DECISION**.
- Device matrix, backend hosting, test configuration, and all verification outcomes: **NOT YET VERIFIED**.
- Whether to pursue any optional award category: **NEEDS DECISION**.

## Sources

- RevenueCat Shipaton 2026 Official Rules, Devpost, accessed September 28, 2026: https://revenuecat-shipaton-2026.devpost.com/rules
- Project direction supplied for this repository. It establishes project decisions but is not a source of competition requirements.
- `docs/backend-snapshot-audit.md`, read for this product-decision update. It establishes observed backend capabilities and snapshot blockers; it does not select Android implementation details.
