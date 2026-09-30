package id.hanifalfaqih.aienglishinterview.core.voice

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Test double for VoiceRecognizer that emits scripted events.
 * Used to verify voice-only retry flow without actual audio capture.
 */
class FakeVoiceRecognizer : VoiceRecognizer {
    private val _events = MutableSharedFlow<RecognitionEvent>(extraBufferCapacity = 10)
    override val events: Flow<RecognitionEvent> = _events.asSharedFlow()

    var startListeningCalls = 0
    var stopListeningCalls = 0
    var releaseCalls = 0

    // Aliases for backward compatibility with existing tests
    val startCalls: Int get() = startListeningCalls
    val stopCalls: Int get() = stopListeningCalls

    override fun startListening() {
        startListeningCalls++
    }

    override fun stopListening() {
        stopListeningCalls++
    }

    override fun release() {
        releaseCalls++
    }

    /** Emit any RecognitionEvent */
    fun emit(event: RecognitionEvent) {
        _events.tryEmit(event)
    }

    /** Emit a FinalAudio event to simulate captured audio */
    fun emitAudio(audio: ByteArray) {
        _events.tryEmit(RecognitionEvent.FinalAudio(audio))
    }

    /** Emit an Error event to simulate recognition failure */
    fun emitError(message: String) {
        _events.tryEmit(RecognitionEvent.Error(message))
    }
}
