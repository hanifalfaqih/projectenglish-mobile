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
) : ViewModel() {

    var uiState by mutableStateOf<FeedbackUiState>(FeedbackUiState.Loading)
        private set

    var practiceAgainState by mutableStateOf<PracticeAgainState>(PracticeAgainState.Idle)
        private set

    private var loading = false
    private var practicing = false

    init {
        load()
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
     * on the same experience profile; everyone else is routed to the
     * paywall exactly once per tap (no conversation is created). The closed
     * conversation is never reused or mutated.
     */
    fun practiceAgain() {
        if (practicing) return
        practicing = true
        practiceAgainState = PracticeAgainState.Working
        viewModelScope.launch {
            monetization.refresh()
            val premium =
                (monetization.premiumState.value as? PremiumState.Determined)?.isPremium == true
            if (!premium) {
                practiceAgainState = PracticeAgainState.RequiresPurchase
                practicing = false
                return@launch
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
     */
    fun requestPractice(feedbackItem: AnswerFeedback) {
        if (!feedbackItem.practiceOpportunity) return
        viewModelScope.launch {
            monetization.refresh()
            val premium =
                (monetization.premiumState.value as? PremiumState.Determined)?.isPremium == true
            if (premium) {
                practiceFormTarget = feedbackItem.answerMessageId
            } else {
                retryGateRequiresPurchase = true
            }
        }
    }

    fun closePracticeForm() {
        practiceFormTarget = null
    }

    /** Consumes the one-shot paywall navigation event. */
    fun onRetryGateNavigated() {
        retryGateRequiresPurchase = false
    }

    /**
     * Submits a retry answer for one feedback item. Eligibility comes only
     * from the server-provided [AnswerFeedback.practiceOpportunity]; nothing
     * is inferred from wording, dimensions, or IDs. Access is re-verified
     * against the same generic premium state before any repository call, so
     * the retry backend is never invoked without entitlement. One
     * retryClientKey (fresh UUID) is generated per operation and retained
     * for its lifecycle.
     */
    fun startRetry(feedbackItem: AnswerFeedback, retryAnswer: String) {
        val target = feedbackItem.answerMessageId
        if (retryStates[target] is AnswerRetryState.Submitting) return
        if (!feedbackItem.practiceOpportunity) {
            retryStates = retryStates + (target to AnswerRetryState.Error(
                message = "This answer is not available for retry.",
            ))
            return
        }
        val questionId = feedbackItem.questionMessageId
        if (questionId == null) {
            retryStates = retryStates + (target to AnswerRetryState.Error(
                message = "This answer is not available for retry.",
            ))
            return
        }
        if (retryAnswer.isBlank()) {
            retryStates = retryStates + (target to AnswerRetryState.Error(
                message = "Write a retry answer first.",
            ))
            return
        }
        val retryClientKey = UUID.randomUUID().toString()
        viewModelScope.launch {
            // Re-check after suspension points: a queued duplicate call must
            // not start a second submission for the same target.
            if (retryStates[target] is AnswerRetryState.Submitting) return@launch
            monetization.refresh()
            val premium =
                (monetization.premiumState.value as? PremiumState.Determined)?.isPremium == true
            if (!premium) {
                retryGateRequiresPurchase = true
                return@launch
            }
            retryStates = retryStates + (target to AnswerRetryState.Submitting)
            when (
                val result = retries.submitRetry(
                    conversationId = conversationId,
                    answerMessageId = target,
                    questionMessageId = questionId,
                    retryAnswer = retryAnswer,
                    retryClientKey = retryClientKey,
                )
            ) {
                is ApiResult.Success -> {
                    val mapped = mapRetryResult(result.value.retry)
                    retryStates = retryStates + (target to mapped)
                    if (mapped !is AnswerRetryState.Error) {
                        practiceFormTarget = null
                    }
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
