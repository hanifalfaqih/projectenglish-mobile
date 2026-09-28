package id.hanifalfaqih.aienglishinterview.feature.interview

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.hanifalfaqih.aienglishinterview.core.network.ApiResult
import id.hanifalfaqih.aienglishinterview.core.voice.RecognitionEvent
import id.hanifalfaqih.aienglishinterview.core.voice.SynthesisEvent
import id.hanifalfaqih.aienglishinterview.core.voice.VoicePhase
import id.hanifalfaqih.aienglishinterview.core.voice.VoiceRecognizer
import id.hanifalfaqih.aienglishinterview.core.voice.VoiceSynthesizer
import id.hanifalfaqih.aienglishinterview.data.model.ChatLine
import id.hanifalfaqih.aienglishinterview.data.repository.ConversationRepository
import java.util.UUID
import kotlinx.coroutines.launch

/**
 * Interview turn state. Owns the transcript, the single turn-submission
 * path (UUID `clientTurnId` per new answer, same id on retry), and the
 * voice session state.
 *
 * Voice engines are optional constructor dependencies: production passes
 * the real provider once selected, tests pass fakes, and null means the
 * provider is still pending (voice UI stays disabled). Voice never
 * submits turns itself — final recognized text flows through [send].
 */
class InterviewViewModel(
    val conversationId: String,
    private val repository: ConversationRepository = ConversationRepository(),
    private val recognizer: VoiceRecognizer? = null,
    private val synthesizer: VoiceSynthesizer? = null,
) : ViewModel() {

    var lines by mutableStateOf(listOf<ChatLine>())
        private set

    var status by mutableStateOf("active")
        private set

    var isClosed by mutableStateOf(false)
        private set

    var sending by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    // --- Voice state ---

    val voiceAvailable: Boolean = recognizer != null && synthesizer != null

    var voicePhase by mutableStateOf(VoicePhase.IDLE)
        private set

    /** Latest partial/final hypothesis for preview. Never submitted directly. */
    var heardText by mutableStateOf("")
        private set

    /**
     * Transient recognized-answer preview: the non-blank Final text, shown
     * as "you said" while its turn is in flight. Cleared when the turn
     * resolves (the transcript lines then carry the message), when a new
     * session starts, or on release — never persisted.
     */
    var pendingVoiceAnswer by mutableStateOf<String?>(null)
        private set

    var voiceError by mutableStateOf<String?>(null)
        private set

    private var pendingRetry: Pair<String, String>? = null
    private var finalConsumed = false

    init {
        if (recognizer != null) {
            viewModelScope.launch {
                recognizer.events.collect { onRecognitionEvent(it) }
            }
        }
        if (synthesizer != null) {
            viewModelScope.launch {
                synthesizer.events.collect { onSynthesisEvent(it) }
            }
        }
    }

    fun send(message: String) {
        val text = message.trim()
        if (text.isEmpty() || sending || isClosed) return
        error = null
        sending = true
        val turnId = UUID.randomUUID().toString()
        pendingRetry = turnId to text
        viewModelScope.launch {
            executeTurn(turnId, text)
        }
    }

    fun retry() {
        val (turnId, text) = pendingRetry ?: return
        if (sending || isClosed) return
        error = null
        sending = true
        viewModelScope.launch {
            executeTurn(turnId, text)
        }
    }

    /** Mic tap with permission already granted. Barges in over TTS. */
    fun startVoiceInput() {
        if (!voiceAvailable || isClosed || sending) return
        if (voicePhase == VoicePhase.LISTENING) return
        synthesizer?.stop()
        voiceError = null
        heardText = ""
        pendingVoiceAnswer = null
        finalConsumed = false
        voicePhase = VoicePhase.LISTENING
        recognizer?.startListening()
    }

    fun cancelVoiceInput() {
        recognizer?.stopListening()
        if (voicePhase == VoicePhase.LISTENING) {
            voicePhase = VoicePhase.IDLE
        }
    }

    fun onVoicePermissionDenied() {
        voiceError = "Microphone permission is required for voice input."
        voicePhase = VoicePhase.IDLE
    }

    /** Called when leaving the screen: no callbacks may outlive the UI. */
    fun releaseVoice() {
        recognizer?.release()
        synthesizer?.release()
        heardText = ""
        pendingVoiceAnswer = null
        if (voicePhase != VoicePhase.IDLE) {
            voicePhase = VoicePhase.IDLE
        }
    }

    private fun onRecognitionEvent(event: RecognitionEvent) {
        when (event) {
            is RecognitionEvent.Partial -> {
                if (voicePhase == VoicePhase.LISTENING) {
                    heardText = event.text
                }
            }
            is RecognitionEvent.Final -> {
                if (voicePhase != VoicePhase.LISTENING || finalConsumed) return
                finalConsumed = true
                recognizer?.stopListening()
                val text = event.text.trim()
                if (text.isEmpty()) {
                    voiceError = "Didn't catch that — try speaking again."
                    voicePhase = VoicePhase.IDLE
                    return
                }
                heardText = text
                pendingVoiceAnswer = text
                voicePhase = VoicePhase.IDLE
                // Single submission path: the existing turn pipeline owns
                // clientTurnId generation and closed-state guards.
                send(text)
            }
            is RecognitionEvent.Error -> {
                recognizer?.stopListening()
                voiceError = event.message
                voicePhase = VoicePhase.IDLE
            }
        }
    }

    private fun onSynthesisEvent(event: SynthesisEvent) {
        when (event) {
            is SynthesisEvent.Done -> {
                if (voicePhase == VoicePhase.SPEAKING) {
                    voicePhase = VoicePhase.IDLE
                }
            }
            is SynthesisEvent.Error -> {
                voiceError = event.message
                if (voicePhase == VoicePhase.SPEAKING) {
                    voicePhase = VoicePhase.IDLE
                }
            }
        }
    }

    private suspend fun executeTurn(turnId: String, text: String) {
        when (val result = repository.sendTurn(conversationId, turnId, text)) {
            is ApiResult.Success -> {
                lines = lines + ChatLine(isUser = true, text = text) +
                    ChatLine(isUser = false, text = result.value.assistantMessage)
                status = result.value.status
                isClosed = result.value.isClosed
                pendingRetry = null
                pendingVoiceAnswer = null
                error = null
                // Speak the final message even when closing, but never
                // restart listening after completion.
                if (synthesizer != null) {
                    if (!isClosed) {
                        voicePhase = VoicePhase.SPEAKING
                    }
                    synthesizer.speak(result.value.assistantMessage)
                }
            }
            is ApiResult.Error -> {
                pendingVoiceAnswer = null
                error = result.message
            }
        }
        sending = false
    }
}
