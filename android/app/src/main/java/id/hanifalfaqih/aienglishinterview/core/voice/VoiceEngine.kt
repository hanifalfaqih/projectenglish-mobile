package id.hanifalfaqih.aienglishinterview.core.voice

import kotlinx.coroutines.flow.Flow

/**
 * Provider-agnostic voice contracts. The interview feature depends only on
 * these interfaces — never on a concrete STT/TTS SDK — so the provider can
 * be swapped without touching ViewModel or UI code.
 *
 * No production provider is wired yet; see [FakeVoiceEngine] (test/dev
 * double only). In particular this is NOT Android SpeechRecognizer: the
 * earlier spike's transcript quality was rejected for production.
 */

/** Events from speech-to-text. Only [Final] may create a backend turn. */
sealed interface RecognitionEvent {
    /** Interim hypothesis; display only, never submit. */
    data class Partial(val text: String) : RecognitionEvent

    /** Completed utterance. Submitted exactly once by the consumer. */
    data class Final(val text: String) : RecognitionEvent

    /** Recognition failed; listening has stopped. */
    data class Error(val message: String) : RecognitionEvent
}

interface VoiceRecognizer {
    /** Begin a listening session. Must be idempotent; concurrent sessions forbidden. */
    fun startListening()

    /** End the current session, gracefully transcribing captured audio. */
    fun stopListening()

    /**
     * Permanent teardown: discard in-flight work and free owned resources.
     * Defaults to [stopListening]; providers owning threads, recorders, or
     * scopes override this. The existing screen calls it on disposal.
     */
    fun release() {
        stopListening()
    }

    /** Hot stream of [RecognitionEvent] for the current session. */
    val events: Flow<RecognitionEvent>
}

/** Events from text-to-speech. */
sealed interface SynthesisEvent {
    data object Done : SynthesisEvent
    data class Error(val message: String) : SynthesisEvent
}

interface VoiceSynthesizer {
    /** Speak the text, cancelling any in-progress utterance. */
    fun speak(text: String)

    /** Cancel any in-progress utterance. */
    fun stop()

    /**
     * Permanent teardown. Defaults to [stop]; providers owning engines
     * override this.
     */
    fun release() {
        stop()
    }

    /** Hot stream of [SynthesisEvent]. */
    val events: Flow<SynthesisEvent>
}

/** UI-visible voice phase. Backend submission state stays authoritative. */
enum class VoicePhase {
    IDLE,
    LISTENING,
    SPEAKING,
}
