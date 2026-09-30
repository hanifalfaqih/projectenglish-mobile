package id.hanifalfaqih.aienglishinterview.core.voice

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Deterministic test/dev doubles for [VoiceRecognizer] and
 * [VoiceSynthesizer]. They produce no audio, require no permission, hold no
 * system resources, and must NEVER ship as the production voice provider.
 * Their only purpose is proving the Android-side pipeline
 * (STT event → turn → TTS) without live credentials or a provider SDK.
 */
class FakeVoiceRecognizer : VoiceRecognizer {
    private val _events = MutableSharedFlow<RecognitionEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<RecognitionEvent> = _events.asSharedFlow()

    var listening = false
        private set
    var startCalls = 0
        private set
    var stopCalls = 0
        private set

    override fun startListening() {
        startCalls++
        listening = true
    }

    override fun stopListening() {
        stopCalls++
        listening = false
    }

    /** Test driver: emit events as if the provider produced them. */
    fun emit(event: RecognitionEvent) {
        _events.tryEmit(event)
    }
}

class FakeVoiceSynthesizer(
    /** When true, [speak] completes immediately with [SynthesisEvent.Done]. */
    var autoComplete: Boolean = true,
) : VoiceSynthesizer {
    private val _events = MutableSharedFlow<SynthesisEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<SynthesisEvent> = _events.asSharedFlow()

    val spoken = mutableListOf<String>()
    val spokenAudio = mutableListOf<ByteArray?>()
    var stopCalls = 0
        private set

    override fun speak(text: String, audio: ByteArray?) {
        spoken.add(text)
        spokenAudio.add(audio)
        if (autoComplete) {
            _events.tryEmit(SynthesisEvent.Done)
        }
    }

    override fun stop() {
        stopCalls++
    }

    /** Test driver for manual-completion mode. */
    fun complete() {
        _events.tryEmit(SynthesisEvent.Done)
    }

    fun fail(message: String) {
        _events.tryEmit(SynthesisEvent.Error(message))
    }
}
