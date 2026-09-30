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

    /**
     * Completed utterance as captured audio. The consumer uploads it for
     * server-side transcription; the returned transcript then flows through
     * the normal turn path exactly once. Kept alongside [Final] (used by
     * fakes and text-equivalent doubles) without changing consumers.
     */
    data class FinalAudio(val audio: ByteArray) : RecognitionEvent {
        override fun equals(other: Any?): Boolean =
            other is FinalAudio && audio.contentEquals(other.audio)

        override fun hashCode(): Int = audio.contentHashCode()
    }

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
    /**
     * Speak the text, cancelling any in-progress utterance. When backend
     * audio bytes are provided, implementations that can play them SHOULD
     * play the audio (it matches the text exactly); implementations without
     * audio playback fall back to synthesizing [text]. The default ignores
     * audio, preserving existing callers.
     */
    fun speak(text: String, audio: ByteArray? = null)

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
    /** User pressed Stop; recognizer is flushing captured audio. No controls. */
    FINALIZING,
    SPEAKING,
}
