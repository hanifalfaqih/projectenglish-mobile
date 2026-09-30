package id.hanifalfaqih.aienglishinterview.feature.feedback

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.hanifalfaqih.aienglishinterview.core.network.ApiResult
import id.hanifalfaqih.aienglishinterview.data.model.AnswerFeedback
import id.hanifalfaqih.aienglishinterview.data.model.Feedback
import id.hanifalfaqih.aienglishinterview.data.model.Retry
import id.hanifalfaqih.aienglishinterview.core.monetization.MonetizationProvider
import id.hanifalfaqih.aienglishinterview.core.monetization.MonetizationRepository
import id.hanifalfaqih.aienglishinterview.core.monetization.PremiumState
import id.hanifalfaqih.aienglishinterview.core.voice.VoiceRecognizer
import id.hanifalfaqih.aienglishinterview.core.voice.RecognitionEvent
import id.hanifalfaqih.aienglishinterview.core.voice.VoicePhase
import id.hanifalfaqih.aienglishinterview.data.repository.ConversationRepository
import id.hanifalfaqih.aienglishinterview.data.repository.FeedbackRepository
import id.hanifalfaqih.aienglishinterview.data.repository.RetryRepository
import kotlinx.coroutines.launch
import java.util.UUID

sealed interface FeedbackUiState {
    data object Loading : FeedbackUiState
    data class Content(val feedback: Feedback) : FeedbackUiState
    data class Error(val message: String) : FeedbackUiState
}

sealed interface PracticeAgainState {
    data object Idle : PracticeAgainState
    data object Working : PracticeAgainState
    data class Error(val message: String) : PracticeAgainState
    data class Done(val conversationId: String) : PracticeAgainState
    /** One-shot: UI must open the paywall, then mark it consumed. */
    data object RequiresPurchase : PracticeAgainState
}

/**
 * M12 targeted-retry state for ONE feedback item, keyed by answerMessageId
 * in [FeedbackViewModel.retryStates]. The original M11 feedback is never
 * modified; retry results live only here.
 */
sealed interface AnswerRetryState {
    data object Idle : AnswerRetryState
    data object Submitting : AnswerRetryState
    /** Artifact returned with usable retry feedback. */
    data class FeedbackAvailable(val retry: Retry) : AnswerRetryState
    /**
     * Artifact persisted but feedback generation failed (backend 201 with
     * failed status). Distinct from submission errors below.
     */
    data class FeedbackFailed(val retry: Retry) : AnswerRetryState
    /** Submission failed before a usable artifact was returned. */
    data class Error(val message: String, val httpCode: Int? = null) : AnswerRetryState
}

/**
 * Feedback for one completed interview. Loads once via the idempotent
 * generate endpoint (201 new / 200 existing are both success); retry
 * re-issues the same call, which the backend answers from the persisted
 * artifact. Duplicate in-flight loads are ignored.
 */
class FeedbackViewModel(
    val conversationId: String,
    private val repository: FeedbackRepository = FeedbackRepository(),
    private val conversations: ConversationRepository = ConversationRepository(),
    private val monetization: MonetizationRepository = MonetizationProvider.repository,
    private val retries: RetryRepository = RetryRepository(),
    private val recognizer: VoiceRecognizer? = null,
) : ViewModel() {

    var uiState by mutableStateOf<FeedbackUiState>(FeedbackUiState.Loading)
        private set

    var practiceAgainState by mutableStateOf<PracticeAgainState>(PracticeAgainState.Idle)
        private set

    private var loading = false
    private var practicing = false

    // --- Voice retry state ---

    var voicePhase by mutableStateOf(VoicePhase.IDLE)
        private set

    var voiceError by mutableStateOf<String?>(null)
        private set

    private var retryTarget: String? = null

    init {
        load()
        if (recognizer != null) {
            viewModelScope.launch {
                recognizer.events.collect { onRecognitionEvent(it) }
            }
        }
    }

    private fun onRecognitionEvent(event: RecognitionEvent) {
        when (event) {
            is RecognitionEvent.FinalAudio -> {
                voicePhase = VoicePhase.IDLE
                if (event.audio.isEmpty()) {
                    voiceError = "No audio recorded. Try again."
                    return
                }
                val target = retryTarget ?: return
                retryTarget = null
                submitVoiceRetry(target, event.audio)
            }
            is RecognitionEvent.Error -> {
                voiceError = event.message
                voicePhase = VoicePhase.IDLE
            }
            is RecognitionEvent.Partial, is RecognitionEvent.Final -> Unit
        }
    }

    fun startVoiceInput(feedbackItem: AnswerFeedback) {
        if (!feedbackItem.practiceOpportunity) {
            // Don't start voice input for ineligible items
            return
        }
        if (voicePhase == VoicePhase.LISTENING) return
        
        // Check current entitlement state synchronously first
        when (val entitlement = monetization.premiumState.value) {
            is PremiumState.Determined -> {
                if (!entitlement.isPremium) {
                    retryGateRequiresPurchase = true
                    return
                }
                // Premium: open form and start listening immediately
                practiceFormTarget = feedbackItem.answerMessageId
                voiceError = null
                retryTarget = feedbackItem.answerMessageId
                voicePhase = VoicePhase.LISTENING
                recognizer?.startListening()
                return
            }
            is PremiumState.Loading, is PremiumState.Unavailable -> {
                // Need to refresh asynchronously
                viewModelScope.launch {
                    monetization.refresh()
                    when (val refreshed = monetization.premiumState.value) {
                        is PremiumState.Determined -> {
                            if (!refreshed.isPremium) {
                                retryGateRequiresPurchase = true
                            } else {
                                practiceFormTarget = feedbackItem.answerMessageId
                                voiceError = null
                                retryTarget = feedbackItem.answerMessageId
                                voicePhase = VoicePhase.LISTENING
                                recognizer?.startListening()
                            }
                        }
                        is PremiumState.Loading, is PremiumState.Unavailable -> {
                            retryStates = retryStates + (
                                feedbackItem.answerMessageId to AnswerRetryState.Error(
                                    message = unresolvedEntitlementMessage(refreshed),
                                )
                                )
                            practiceFormTarget = feedbackItem.answerMessageId
                        }
                    }
                }
            }
        }
    }

    fun cancelVoiceInput() {
        recognizer?.stopListening()
        retryTarget = null
        if (voicePhase == VoicePhase.LISTENING) {
            voicePhase = VoicePhase.IDLE
        }
    }

    fun load() {
        if (loading) return
        loading = true
        uiState = FeedbackUiState.Loading
        viewModelScope.launch {
            when (val result = repository.generateFeedback(conversationId)) {
                is ApiResult.Success -> {
                    uiState = FeedbackUiState.Content(result.value.feedback)
                }
                is ApiResult.Error -> {
                    uiState = FeedbackUiState.Error(result.message)
                }
            }
            loading = false
        }
    }

    fun retry() = load()

    /**
     * Retry interview: premium-gated. Premium users open a NEW conversation
     * on the same experience profile; free users are routed to the paywall
     * exactly once per tap (no conversation is created). The closed
     * conversation is never reused or mutated.
     *
     * UNRESOLVED entitlement is NOT free. A determined-free verdict is the
     * only state that may open the paywall; Loading and Unavailable surface
     * a recoverable error instead, because the paywall cannot sell anything
     * when entitlement is unknown (its offers load through the same
     * unavailable SDK).
     */
    fun practiceAgain() {
        if (practicing) return
        practicing = true
        practiceAgainState = PracticeAgainState.Working
        viewModelScope.launch {
            monetization.refresh()
            when (val entitlement = monetization.premiumState.value) {
                is PremiumState.Determined ->
                    if (!entitlement.isPremium) {
                        practiceAgainState = PracticeAgainState.RequiresPurchase
                        practicing = false
                        return@launch
                    }

                // Still resolving after refresh: no session, no paywall.
                // The error state exposes the existing "Try Again" affordance,
                // which re-runs this method and refreshes entitlement again.
                is PremiumState.Loading -> {
                    practiceAgainState = PracticeAgainState.Error(
                        "Still checking your premium access. Please try again.",
                    )
                    practicing = false
                    return@launch
                }

                // SDK not configured/reachable: no session, no paywall.
                is PremiumState.Unavailable -> {
                    practiceAgainState = PracticeAgainState.Error(
                        "Premium access is unavailable right now. Please try again.",
                    )
                    practicing = false
                    return@launch
                }
            }
            val detail = when (val lookedUp = conversations.getConversation(conversationId)) {
                is ApiResult.Error -> {
                    practiceAgainState = PracticeAgainState.Error(lookedUp.message)
                    practicing = false
                    return@launch
                }
                is ApiResult.Success -> lookedUp.value
            }
            val profileId = detail.experienceProfileId
            if (profileId == null) {
                practiceAgainState = PracticeAgainState.Error(
                    "No experience linked to this interview, so a new session can't start.",
                )
                practicing = false
                return@launch
            }
            when (val created = conversations.createConversation(profileId)) {
                is ApiResult.Error -> {
                    practiceAgainState = PracticeAgainState.Error(created.message)
                }
                is ApiResult.Success -> {
                    practiceAgainState = PracticeAgainState.Done(created.value.id)
                }
            }
            practicing = false
        }
    }

    /** Consumes the one-shot paywall navigation event. */
    fun onPaywallNavigated() {
        if (practiceAgainState is PracticeAgainState.RequiresPurchase) {
            practiceAgainState = PracticeAgainState.Idle
        }
    }

    // --- M12 targeted retry (one answer at a time) ---

    /**
     * Retry state per feedback item, keyed by answerMessageId. The original
     * M11 feedback in [uiState] is never modified; results live only here.
     */
    var retryStates by mutableStateOf<Map<String, AnswerRetryState>>(emptyMap())
        private set

    /**
     * Which feedback item's retry form is open (answerMessageId), owned here
     * so the premium gate can decide open-vs-paywall before any input shows.
     */
    var practiceFormTarget by mutableStateOf<String?>(null)
        private set

    /**
     * One-shot M12 gate event: eligible item + no premium access. The UI
     * opens the existing paywall and consumes it via [onRetryGateNavigated].
     * Reuses the generic premium entitlement state — no second source of
     * truth, no M12-specific entitlement.
     */
    var retryGateRequiresPurchase by mutableStateOf(false)
        private set

    /**
     * Activates the targeted-practice affordance for one item. Server-owned
     * [AnswerFeedback.practiceOpportunity] decides eligibility; the existing
     * premium entitlement decides access. Ineligible items never touch
     * monetization and never open the form.
     *
     * UNRESOLVED entitlement is NOT free: only a determined-free verdict
     * routes to the paywall. [PremiumState.Loading] and
     * [PremiumState.Unavailable] surface an inline recoverable error with
     * the form open, because the paywall cannot sell anything while
     * entitlement is unknown — its offers load through the same
     * unavailable SDK. Submission stays gated by [startRetry], so opening
     * the form grants nothing.
     */
    fun requestPractice(feedbackItem: AnswerFeedback) {
        if (!feedbackItem.practiceOpportunity) return
        viewModelScope.launch {
            monetization.refresh()
            when (val entitlement = monetization.premiumState.value) {
                is PremiumState.Determined ->
                    if (entitlement.isPremium) {
                        practiceFormTarget = feedbackItem.answerMessageId
                    } else {
                        retryGateRequiresPurchase = true
                    }

                is PremiumState.Loading, is PremiumState.Unavailable -> {
                    val target = feedbackItem.answerMessageId
                    retryStates = retryStates + (
                        target to AnswerRetryState.Error(
                            message = unresolvedEntitlementMessage(entitlement),
                        )
                        )
                    // Keep the form open so the existing Error rendering
                    // shows the message above the preserved input, and the
                    // user can resubmit (which re-checks entitlement).
                    practiceFormTarget = target
                }
            }
        }
    }

    /**
     * User-facing copy for an entitlement that could not be resolved. Kept
     * separate from a determined-free verdict, which routes to the paywall
     * instead. Reused by both targeted-retry gates.
     */
    private fun unresolvedEntitlementMessage(state: PremiumState): String =
        when (state) {
            is PremiumState.Unavailable ->
                "Premium access is unavailable right now. Please try again."
            else -> "Still checking your premium access. Please try again."
        }

    fun closePracticeForm() {
        practiceFormTarget = null
    }

    /** Consumes the one-shot paywall navigation event. */
    fun onRetryGateNavigated() {
        retryGateRequiresPurchase = false
    }

    /**
     * Submits a voice retry answer for one feedback item. Captures audio,
     * transcribes it via /transcription, then submits the transcript to
     * /retries. Eligibility comes only from the server-provided
     * [AnswerFeedback.practiceOpportunity]; nothing is inferred from wording,
     * dimensions, or IDs. Access is re-verified against the same generic
     * premium state before any repository call, so the retry backend is never
     * invoked without entitlement. One retryClientKey (fresh UUID) is generated
     * per operation and retained for its lifecycle.
     */
    private fun submitVoiceRetry(target: String, audio: ByteArray) {
        if (retryStates[target] is AnswerRetryState.Submitting) return
        val retryClientKey = UUID.randomUUID().toString()
        viewModelScope.launch {
            // Re-check after suspension points: a queued duplicate call must
            // not start a second submission for the same target.
            if (retryStates[target] is AnswerRetryState.Submitting) return@launch
            monetization.refresh()
            when (val entitlement = monetization.premiumState.value) {
                // Only a determined-free verdict routes to the paywall.
                is PremiumState.Determined ->
                    if (!entitlement.isPremium) {
                        retryGateRequiresPurchase = true
                        return@launch
                    }

                // Unresolved entitlement must not read as free, and must not
                // send the user to a paywall that cannot load offers. Surface
                // a recoverable inline error instead. The retry backend is
                // still never called.
                is PremiumState.Loading, is PremiumState.Unavailable -> {
                    retryStates = retryStates + (
                        target to AnswerRetryState.Error(
                            message = unresolvedEntitlementMessage(entitlement),
                        )
                        )
                    return@launch
                }
            }
            retryStates = retryStates + (target to AnswerRetryState.Submitting)
            // Transcribe audio first
            val transcript = when (val result = conversations.transcribeAudio(audio)) {
                is ApiResult.Success -> result.value
                is ApiResult.Error -> {
                    retryStates = retryStates + (target to AnswerRetryState.Error(
                        message = result.message,
                        httpCode = result.httpCode,
                    ))
                    return@launch
                }
            }
            if (transcript.isBlank()) {
                retryStates = retryStates + (target to AnswerRetryState.Error(
                    message = "No speech detected. Try again.",
                ))
                return@launch
            }
            // Find the feedback item to get questionMessageId
            val feedback = (uiState as? FeedbackUiState.Content)?.feedback
            val feedbackItem = feedback?.answerItems?.find { it.answerMessageId == target }
            val questionId = feedbackItem?.questionMessageId
            if (questionId == null) {
                retryStates = retryStates + (target to AnswerRetryState.Error(
                    message = "This answer is not available for retry.",
                ))
                return@launch
            }
            // Submit transcript to /retries
            when (
                val result = retries.submitRetry(
                    conversationId = conversationId,
                    answerMessageId = target,
                    questionMessageId = questionId,
                    retryAnswer = transcript,
                    retryClientKey = retryClientKey,
                )
            ) {
                is ApiResult.Success -> {
                    val mapped = mapRetryResult(result.value.retry)
                    retryStates = retryStates + (target to mapped)
                    practiceFormTarget = null
                }
                is ApiResult.Error -> {
                    retryStates = retryStates + (target to AnswerRetryState.Error(
                        message = result.message,
                        httpCode = result.httpCode,
                    ))
                }
            }
        }
    }

    /**
     * Loads an already-persisted retry without submitting. A 404 simply
     * means no retry exists (Idle); anything else surfaces as Error.
     */
    fun loadCurrentRetry(answerMessageId: String) {
        if (retryStates[answerMessageId] is AnswerRetryState.Submitting) return
        viewModelScope.launch {
            when (
                val result = retries.getCurrentRetry(conversationId, answerMessageId)
            ) {
                is ApiResult.Success -> {
                    retryStates = retryStates + (answerMessageId to mapRetryResult(result.value))
                }
                is ApiResult.Error -> {
                    retryStates = retryStates + (
                        answerMessageId to if (result.httpCode == 404) {
                            AnswerRetryState.Idle
                        } else {
                            AnswerRetryState.Error(
                                message = result.message,
                                httpCode = result.httpCode,
                            )
                        }
                        )
                }
            }
        }
    }

    /**
     * Regenerates feedback for an existing retry artifact. Operates only on
     * a known artifact target; never invents one and never touches M11 state.
     */
    fun regenerateRetry(answerMessageId: String) {
        if (retryStates[answerMessageId] is AnswerRetryState.Submitting) return
        retryStates = retryStates + (answerMessageId to AnswerRetryState.Submitting)
        viewModelScope.launch {
            when (
                val result = retries.regenerateRetryFeedback(conversationId, answerMessageId)
            ) {
                is ApiResult.Success -> {
                    retryStates = retryStates + (answerMessageId to mapRetryResult(result.value))
                }
                is ApiResult.Error -> {
                    retryStates = retryStates + (answerMessageId to AnswerRetryState.Error(
                        message = result.message,
                        httpCode = result.httpCode,
                    ))
                }
            }
        }
    }

    /**
     * Maps a returned artifact to state. 200 and 201 are identical product
     * outcomes (both carry the artifact). A present feedback payload means
     * usable feedback; anything else (failed status or null payload) is the
     * failed-artifact state — never manufactured into success.
     */
    private fun mapRetryResult(retry: Retry): AnswerRetryState =
        if (retry.feedback != null) {
            AnswerRetryState.FeedbackAvailable(retry)
        } else {
            AnswerRetryState.FeedbackFailed(retry)
        }
}
