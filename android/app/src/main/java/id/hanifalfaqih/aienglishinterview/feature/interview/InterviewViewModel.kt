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
import id.hanifalfaqih.aienglishinterview.core.voice.BackendAudioPlayer
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
 * the backend-audio player plus the platform synthesizer fallback, tests
 * pass fakes, and null means the provider is still pending (voice UI stays
 * disabled). Voice never submits turns itself — audio flows through
 * [submitVoiceTurn], text through [send].
 */
class InterviewViewModel(
    val conversationId: String,
    private val repository: ConversationRepository = ConversationRepository(),
    private val recognizer: VoiceRecognizer? = null,
    private val synthesizer: VoiceSynthesizer? = null,
    private val audioPlayer: VoiceSynthesizer? = BackendAudioPlayer(),
) : ViewModel() {

    var lines by mutableStateOf(listOf<ChatLine>())
        private set

    var status by mutableStateOf("active")
        private set

    var isClosed by mutableStateOf(false)
        private set

    var sending by mutableStateOf(false)
        private set

    /**
     * AI-first opening in flight (generate + synthesize + autoplay).
     * While true the microphone and text send stay disabled: the
     * interviewer speaks first, the user cannot jump ahead.
     */
    var opening by mutableStateOf(false)
        private set

    /** Opening failed and the interview has no content yet: offer retry. */
    var needsOpeningRetry by mutableStateOf(false)
        private set

    /**
     * Completion may only advance after the final interviewer audio has
     * finished: navigating away releases the player and would cut it off.
     */
    val canComplete: Boolean get() = isClosed && voicePhase != VoicePhase.SPEAKING

    private var openingStarted = false

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

    /**
     * Recoverable non-error notice (e.g. backend voice output unavailable,
     * falling back to on-device speech). Informational only; conversation
     * state is unaffected.
     */
    var voiceNotice by mutableStateOf<String?>(null)
        private set

    private var pendingRetry: Pair<String, String>? = null
    private var pendingVoiceRetry: Pair<String, ByteArray>? = null
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
        if (audioPlayer != null) {
            viewModelScope.launch {
                audioPlayer.events.collect { onSynthesisEvent(it) }
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
        if (sending || isClosed) return
        error = null
        val text = pendingRetry
        if (text != null) {
            sending = true
            viewModelScope.launch {
                executeTurn(text.first, text.second)
            }
            return
        }
        // Voice retry resubmits the SAME captured audio with the SAME
        // clientTurnId: the backend replays idempotently instead of
        // creating a duplicate turn.
        val voice = pendingVoiceRetry ?: return
        sending = true
        viewModelScope.launch {
            executeVoiceTurn(voice.first, voice.second)
        }
    }

    /**
     * AI-first opening trigger. Idempotent per ViewModel lifetime, so
     * recomposition and configuration change re-invoke safely; the backend
     * additionally replays an untouched opening instead of duplicating it.
     * Appends the interviewer's first line and autoplays it through the
     * existing audio path (backend PCM first, on-device fallback otherwise).
     */
    fun startOpening() {
        if (openingStarted || isClosed) return
        openingStarted = true
        opening = true
        needsOpeningRetry = false
        error = null
        viewModelScope.launch {
            when (val result = repository.getOpening(conversationId)) {
                is ApiResult.Success -> {
                    val openingTurn = result.value
                    lines = lines + ChatLine(isUser = false, text = openingTurn.assistantMessage)
                    playAssistantResponse(
                        openingTurn.assistantMessage,
                        openingTurn.audio,
                        openingTurn.audioError,
                    )
                }
                is ApiResult.Error -> {
                    // 409 is ambiguous: turns exist (opening taken elsewhere)
                    // or the conversation is closed. Resolve authoritatively
                    // so a closed interview never becomes mic-ready.
                    if (result.httpCode == 409) {
                        needsOpeningRetry = false
                        viewModelScope.launch {
                            when (val detail = repository.getConversation(conversationId)) {
                                is ApiResult.Success -> {
                                    if (detail.value.isClosed) {
                                        isClosed = true
                                    }
                                }
                                is ApiResult.Error -> Unit
                            }
                        }
                    } else {
                        error = result.message
                        needsOpeningRetry = true
                    }
                }
            }
            opening = false
        }
    }

    /** Retries a failed opening (safe: the backend replays untouched openings). */
    fun retryOpening() {
        if (opening) return
        openingStarted = false
        startOpening()
    }

    /** Mic tap with permission already granted. Barges in over TTS. */
    fun startVoiceInput() {
        if (!voiceAvailable || isClosed || sending || opening) return
        if (voicePhase == VoicePhase.LISTENING) return
        synthesizer?.stop()
        audioPlayer?.stop()
        voiceError = null
        voiceNotice = null
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
        audioPlayer?.release()
        heardText = ""
        pendingVoiceAnswer = null
        voiceNotice = null
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
            is RecognitionEvent.FinalAudio -> {
                // Deliberately NO voicePhase check: manual stop transitions
                // to IDLE before finalization emits, so requiring LISTENING
                // would deterministically drop every manual-stop result.
                // Session validity is owned by finalConsumed plus the
                // recognizer's sessionId guards; submitVoiceTurn enforces
                // the sending/isClosed guards.
                if (finalConsumed) return
                finalConsumed = true
                recognizer?.stopListening()
                if (event.audio.isEmpty()) {
                    voiceError = "Didn't catch that — try speaking again."
                    voicePhase = VoicePhase.IDLE
                    return
                }
                voicePhase = VoicePhase.IDLE
                // Voice turns upload captured audio for server-side
                // transcription; clientTurnId ownership and guards match send().
                submitVoiceTurn(event.audio)
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
                applyTurnSuccess(
                    userText = text,
                    assistantMessage = result.value.assistantMessage,
                    status = result.value.status,
                    isClosed = result.value.isClosed,
                )
                pendingRetry = null
            }
            is ApiResult.Error -> {
                pendingVoiceAnswer = null
                error = result.message
            }
        }
        sending = false
    }

    /**
     * Voice-turn submission: uploads captured audio; the backend returns the
     * transcript together with the turn result. UUID ownership, guards, and
     * retry semantics match [send]; voice failures simply re-arm the mic
     * (no text to retry with).
     */
    private fun submitVoiceTurn(audio: ByteArray) {
        if (sending || isClosed) return
        error = null
        voiceNotice = null
        sending = true
        val turnId = UUID.randomUUID().toString()
        pendingVoiceRetry = turnId to audio
        viewModelScope.launch {
            executeVoiceTurn(turnId, audio)
        }
    }

    private suspend fun executeVoiceTurn(turnId: String, audio: ByteArray) {
        when (val result = repository.sendVoiceTurn(conversationId, turnId, audio)) {
            is ApiResult.Success -> {
                val turn = result.value
                applyTurnState(
                    userText = turn.transcript,
                    assistantMessage = turn.assistantMessage,
                    status = turn.status,
                    isClosed = turn.isClosed,
                )
                playAssistantResponse(turn.assistantMessage, turn.audio, turn.audioError)
                pendingVoiceRetry = null
            }
            is ApiResult.Error -> {
                error = result.message
            }
        }
        sending = false
    }

    private fun applyTurnSuccess(
        userText: String,
        assistantMessage: String,
        status: String,
        isClosed: Boolean,
    ) {
        applyTurnState(userText, assistantMessage, status, isClosed)
        // Text turns always use the on-device synthesizer. The phase is
        // set even when closing so the UI can gate completion on the
        // final audio; nothing transitions back to listening on its own.
        if (synthesizer != null) {
            voicePhase = VoicePhase.SPEAKING
            synthesizer.speak(assistantMessage)
        }
    }

    private fun applyTurnState(
        userText: String,
        assistantMessage: String,
        status: String,
        isClosed: Boolean,
    ) {
        lines = lines + ChatLine(isUser = true, text = userText) +
            ChatLine(isUser = false, text = assistantMessage)
        this.status = status
        this.isClosed = isClosed
        pendingVoiceAnswer = null
        error = null
    }

    /**
     * Backend audio is authoritative presentation: play it when present,
     * otherwise fall back to on-device speech (if healthy) with a
     * recoverable notice. Conversation state is already committed either
     * way — TTS is presentation only.
     */
    private fun playAssistantResponse(
        assistantMessage: String,
        audio: ByteArray?,
        audioError: String?,
    ) {
        // Speak the final message even when closing, but never
        // restart listening after completion. The phase is set even when
        // closing so completion waits for the final audio (see canComplete).
        if (audio != null && audioPlayer != null) {
            voicePhase = VoicePhase.SPEAKING
            audioPlayer.speak(assistantMessage, audio)
            return
        }
        if (synthesizer != null) {
            voicePhase = VoicePhase.SPEAKING
            synthesizer.speak(assistantMessage)
        }
        if (audioError != null) {
            voiceNotice = audioError
        }
    }
}
