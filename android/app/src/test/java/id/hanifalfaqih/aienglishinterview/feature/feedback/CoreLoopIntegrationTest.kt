package id.hanifalfaqih.aienglishinterview.feature.feedback

import android.app.Activity
import id.hanifalfaqih.aienglishinterview.core.monetization.FakeMonetization
import id.hanifalfaqih.aienglishinterview.core.monetization.MonetizationRepository
import id.hanifalfaqih.aienglishinterview.core.monetization.PremiumState
import id.hanifalfaqih.aienglishinterview.core.monetization.PurchaseOutcome
import id.hanifalfaqih.aienglishinterview.core.voice.FakeVoiceRecognizer
import id.hanifalfaqih.aienglishinterview.core.voice.FakeVoiceSynthesizer
import id.hanifalfaqih.aienglishinterview.core.voice.VoicePhase
import id.hanifalfaqih.aienglishinterview.data.remote.ConversationStateDto
import id.hanifalfaqih.aienglishinterview.data.remote.CreateConversationRequest
import id.hanifalfaqih.aienglishinterview.data.remote.CreateConversationResponse
import id.hanifalfaqih.aienglishinterview.data.remote.CreateExperienceProfileRequest
import id.hanifalfaqih.aienglishinterview.data.remote.CreateExperienceProfileResponse
import id.hanifalfaqih.aienglishinterview.data.remote.FeedbackDto
import id.hanifalfaqih.aienglishinterview.data.remote.GetConversationResponse
import id.hanifalfaqih.aienglishinterview.data.remote.InterviewApi
import id.hanifalfaqih.aienglishinterview.data.remote.OpeningResponse
import id.hanifalfaqih.aienglishinterview.data.remote.ResumeParseResponse
import id.hanifalfaqih.aienglishinterview.data.remote.RetryResponseDto
import id.hanifalfaqih.aienglishinterview.data.remote.SendTurnRequest
import id.hanifalfaqih.aienglishinterview.data.remote.SendTurnResponse
import id.hanifalfaqih.aienglishinterview.data.remote.SubmitRetryRequestDto
import id.hanifalfaqih.aienglishinterview.data.remote.VoiceTurnResponse
import id.hanifalfaqih.aienglishinterview.data.repository.ConversationRepository
import id.hanifalfaqih.aienglishinterview.data.repository.FeedbackRepository
import id.hanifalfaqih.aienglishinterview.feature.interview.InterviewViewModel
import id.hanifalfaqih.aienglishinterview.feature.premium.OffersState
import id.hanifalfaqih.aienglishinterview.feature.premium.PremiumViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.MultipartBody
import okhttp3.RequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import retrofit2.Response

/**
 * Integration-level proof of the core product loop across its REAL
 * production boundaries:
 *
 *   InterviewViewModel (complete)
 *     -> FeedbackViewModel (result)
 *     -> practiceAgain (entitlement gate)
 *     -> PremiumViewModel (paywall + purchase)
 *     -> FakeMonetization (entitlement seam, shared by BOTH ViewModels)
 *     -> FeedbackViewModel.practiceAgain (new session)
 *     -> InterviewViewModel for the NEW conversation (fresh state)
 *
 * Only the HTTP boundary and the payment SDK are faked, matching the
 * project's existing seams ([FakeMonetization], hand-written
 * [InterviewApi] fakes). No real network, no real payment provider, no
 * device, no Compose rendering.
 *
 * Deliberately does NOT re-test contracts already covered by
 * `FeedbackViewModelTest` (practice-again creation, gating, duplicate
 * taps), `PremiumViewModelTest` (offers/purchase messages) or
 * `FakeMonetizationTest` (entitlement transitions). This file proves the
 * cross-ViewModel loop and new-session freshness that those layer tests
 * cannot observe individually.
 */

/**
 * HTTP boundary double for the loop only: closing turn, feedback
 * generation, conversation lookup, and new-conversation creation. Every
 * other route throws so an accidental call outside this flow fails loudly
 * instead of silently passing.
 */
private class CoreLoopApi(
    /** Id returned when a new practice session is created. */
    var newConversationId: String = "conv-2",
    /** Experience profile the closed conversation is linked to. */
    var experienceProfileId: String? = "profile-9",
) : InterviewApi {
    val createdProfileIds = mutableListOf<String?>()
    var feedbackCalls = 0
    var turnCalls = 0
    /** Conversation ids an opening was requested for (freshness evidence). */
    val openingConversationIds = mutableListOf<String>()

    override suspend fun sendTurn(
        conversationId: String,
        body: SendTurnRequest,
    ): SendTurnResponse {
        turnCalls++
        // The backend closes the interview: this is what makes it complete.
        return SendTurnResponse(
            assistantMessage = "That concludes the interview. Thank you.",
            state = ConversationStateDto("wrap_up", emptyList(), null, 8),
            status = "closed",
            closing = true,
        )
    }

    override suspend fun generateFeedback(conversationId: String): Response<FeedbackDto> {
        feedbackCalls++
        return Response.success(
            FeedbackDto(
                conversationId = conversationId,
                promptVersion = "v1",
                overall = "Clear structure and confident delivery.",
                answerItems = emptyList(),
                professionalCommunication = listOf("Steady pacing."),
                createdAt = "2026-09-30T00:00:00.000Z",
            ),
        )
    }

    override suspend fun getConversation(conversationId: String): GetConversationResponse =
        GetConversationResponse(
            id = conversationId,
            status = "closed",
            state = ConversationStateDto("wrap_up", emptyList(), null, 8),
            experienceProfileId = experienceProfileId,
        )

    override suspend fun createConversation(
        body: CreateConversationRequest,
    ): CreateConversationResponse {
        createdProfileIds.add(body.experienceProfileId)
        // A genuinely new session: fresh id, active, empty intro state.
        return CreateConversationResponse(
            id = newConversationId,
            status = "active",
            state = ConversationStateDto("intro", emptyList(), null, 0),
        )
    }

    override suspend fun getOpening(conversationId: String): OpeningResponse {
        openingConversationIds.add(conversationId)
        return OpeningResponse("Welcome back. Tell me about your experience.", null, null)
    }

    // --- Out of scope for this loop. ---

    override suspend fun createExperienceProfile(
        body: CreateExperienceProfileRequest,
    ): CreateExperienceProfileResponse = throw UnsupportedOperationException()
    override suspend fun parseResume(file: MultipartBody.Part): ResumeParseResponse =
        throw UnsupportedOperationException()
    override suspend fun getFeedback(conversationId: String): FeedbackDto =
        throw UnsupportedOperationException()
    override suspend fun sendVoiceTurn(
        conversationId: String,
        audio: MultipartBody.Part,
        clientTurnId: RequestBody,
    ): VoiceTurnResponse = throw UnsupportedOperationException()
    override suspend fun submitRetry(
        conversationId: String,
        body: SubmitRetryRequestDto,
    ): Response<RetryResponseDto> = throw UnsupportedOperationException()
    override suspend fun getCurrentRetry(
        conversationId: String,
        answerMessageId: String,
    ): RetryResponseDto = throw UnsupportedOperationException()
    override suspend fun regenerateRetryFeedback(
        conversationId: String,
        answerMessageId: String,
    ): RetryResponseDto = throw UnsupportedOperationException()
}

@OptIn(ExperimentalCoroutinesApi::class)
class CoreLoopIntegrationTest {

    private val dispatcher = StandardTestDispatcher()
    private val activity: Activity = mock(Activity::class.java)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Closed-conversation interview ViewModel using only test doubles. */
    private fun interviewVm(
        conversationId: String,
        api: InterviewApi,
        synthesizer: FakeVoiceSynthesizer = FakeVoiceSynthesizer(),
    ) = InterviewViewModel(
        conversationId = conversationId,
        repository = ConversationRepository(api),
        recognizer = FakeVoiceRecognizer(),
        synthesizer = synthesizer,
        audioPlayer = FakeVoiceSynthesizer(),
    )

    private fun feedbackVm(
        conversationId: String,
        api: InterviewApi,
        monetization: MonetizationRepository,
    ) = FeedbackViewModel(
        conversationId,
        FeedbackRepository(api),
        ConversationRepository(api),
        monetization,
    )

    /**
     * THE FULL CORE LOOP.
     *
     * Complete -> Result -> Practice Again -> entitlement DENIED -> Paywall
     * -> purchase SUCCESS -> entitlement GRANTED -> Practice Again
     * -> new session -> fresh practice state.
     *
     * Both ViewModels share ONE [FakeMonetization], so the purchase made
     * through [PremiumViewModel] must become visible to
     * [FeedbackViewModel.practiceAgain] through the real entitlement seam.
     */
    @Test
    fun coreLoop_completeToPaywallToPurchaseToFreshNewSession() = runTest(dispatcher) {
        val api = CoreLoopApi()
        val monetization = FakeMonetization(isPremium = false)

        // --- Stage 1: COMPLETE through the production interview boundary. ---
        val interview = interviewVm("conv-1", api)
        advanceUntilIdle()
        interview.send("My final answer.")
        advanceUntilIdle()

        assertTrue("interview must be closed by the backend", interview.isClosed)
        assertTrue("completion gate must open once final audio ends", interview.canComplete)
        assertEquals("closed", interview.status)

        // --- Stage 2: RESULT becomes available for the same conversation. ---
        val feedback = feedbackVm("conv-1", api, monetization)
        advanceUntilIdle()

        val content = feedback.uiState as? FeedbackUiState.Content
        assertTrue("feedback (result) must be available", content != null)
        assertEquals("Clear structure and confident delivery.", content!!.feedback.overall)
        assertEquals(1, api.feedbackCalls)

        // --- Stage 3: PRACTICE AGAIN as a FREE user -> paywall, no session. ---
        assertFalse((monetization.premiumState.value as PremiumState.Determined).isPremium)
        feedback.practiceAgain()
        advanceUntilIdle()

        assertTrue(
            "free user must be routed to the paywall",
            feedback.practiceAgainState is PracticeAgainState.RequiresPurchase,
        )
        assertTrue("no session may be created before entitlement", api.createdProfileIds.isEmpty())
        assertTrue("entitlement must be re-evaluated", monetization.refreshCalls >= 1)

        // UI consumes the one-shot paywall event (AppNavHost navigates here).
        feedback.onPaywallNavigated()
        assertEquals(PracticeAgainState.Idle, feedback.practiceAgainState)

        // --- Stage 4: PAYWALL + PURCHASE through the real paywall ViewModel. ---
        val paywall = PremiumViewModel(monetization)
        advanceUntilIdle()

        assertEquals(PremiumState.Determined(false), paywall.premiumState)
        assertTrue("paywall must show purchasable offers", paywall.offersState is OffersState.Loaded)

        paywall.purchase(activity, "monthly")
        advanceUntilIdle()

        assertEquals(
            "successful purchase must flip the shared entitlement",
            PremiumState.Determined(true),
            paywall.premiumState,
        )
        assertEquals(listOf("monthly"), monetization.purchaseCalls)

        // --- Stage 5: PRACTICE AGAIN as PREMIUM -> genuinely new session. ---
        feedback.practiceAgain()
        advanceUntilIdle()

        val done = feedback.practiceAgainState as? PracticeAgainState.Done
        assertTrue("premium user must get a new session", done != null)
        assertEquals("conv-2", done!!.conversationId)
        assertNotEquals("new session must have a new identity", "conv-1", done.conversationId)
        assertEquals(
            "new session must reuse the SAME experience profile",
            listOf<String?>("profile-9"),
            api.createdProfileIds,
        )

        // --- Stage 6: the new session starts FRESH, with no stale state. ---
        val newSession = interviewVm(done.conversationId, api)
        advanceUntilIdle()
        newSession.startOpening()
        advanceUntilIdle()

        assertTrue("no stale transcript from the previous session", newSession.lines.size <= 1)
        assertEquals(
            "opening must target the NEW conversation only",
            listOf("conv-2"),
            api.openingConversationIds,
        )
        assertFalse("new session must not be closed", newSession.isClosed)
        assertEquals("active", newSession.status)
        assertNull("no stale error state", newSession.error)
        assertEquals(VoicePhase.IDLE, newSession.voicePhase)
        assertTrue(
            "new session shows only its own opening, not the old transcript",
            newSession.lines.none { it.text == "My final answer." },
        )

        // No stale RESULT state for the new session id.
        val newFeedback = feedbackVm(done.conversationId, api, monetization)
        advanceUntilIdle()
        val newContent = newFeedback.uiState as? FeedbackUiState.Content
        assertEquals(2, api.feedbackCalls)
        assertEquals(done.conversationId, newContent!!.feedback.conversationId)

        // --- The completed session is never mutated or resumed. ---
        assertTrue("the closed conversation stays closed", interview.isClosed)
        assertEquals(1, api.turnCalls)
    }

    /**
     * New-session freshness in isolation: a created practice session must
     * carry no transcript, no closed state, and no result state from the
     * session it was launched from.
     */
    @Test
    fun practiceAgain_newSessionCarriesNoStaleState() = runTest(dispatcher) {
        val api = CoreLoopApi()
        val monetization = FakeMonetization(isPremium = true)
        val feedback = feedbackVm("conv-1", api, monetization)
        advanceUntilIdle()

        // Old session has a result; the new one must not inherit it.
        assertTrue(feedback.uiState is FeedbackUiState.Content)

        feedback.practiceAgain()
        advanceUntilIdle()
        val newId = (feedback.practiceAgainState as PracticeAgainState.Done).conversationId
        assertEquals("conv-2", newId)

        val newSession = interviewVm(newId, api)
        advanceUntilIdle()

        // Fresh identity and initial state, before any opening is requested.
        assertTrue(newSession.lines.isEmpty())
        assertFalse(newSession.isClosed)
        assertEquals("active", newSession.status)
        assertNull(newSession.error)
        assertFalse(newSession.sending)
        assertEquals(VoicePhase.IDLE, newSession.voicePhase)
        assertTrue(api.openingConversationIds.isEmpty())

        // The old FeedbackViewModel still points at conv-1: no cross-talk.
        assertEquals("conv-1", feedback.conversationId)
        assertTrue(feedback.practiceAgainState is PracticeAgainState.Done)
    }

    /**
     * Purchase CANCELLATION: the user must not become premium, must stay in
     * a recoverable paywall state, and no practice session may be created.
     * Uses the existing [FakeMonetization] purchase seam.
     */
    @Test
    fun purchaseCancelled_keepsUserFreeGatedAndRecoverable() = runTest(dispatcher) {
        val api = CoreLoopApi()
        val monetization = FakeMonetization(isPremium = false).apply {
            purchaseOutcome = PurchaseOutcome.Cancelled
        }
        val feedback = feedbackVm("conv-1", api, monetization)
        advanceUntilIdle()

        feedback.practiceAgain()
        advanceUntilIdle()
        assertTrue(feedback.practiceAgainState is PracticeAgainState.RequiresPurchase)
        feedback.onPaywallNavigated()

        val paywall = PremiumViewModel(monetization)
        advanceUntilIdle()
        paywall.purchase(activity, "monthly")
        advanceUntilIdle()

        // Not premium, and the paywall reports the cancellation.
        assertEquals(PremiumState.Determined(false), paywall.premiumState)
        assertTrue(paywall.message?.contains("cancelled", ignoreCase = true) == true)
        assertFalse(paywall.purchasing)
        // Offers remain available: the paywall is still usable.
        assertTrue(paywall.offersState is OffersState.Loaded)

        // Practice Again is still gated; no unauthorized session was created.
        feedback.practiceAgain()
        advanceUntilIdle()
        assertTrue(feedback.practiceAgainState is PracticeAgainState.RequiresPurchase)
        assertTrue(api.createdProfileIds.isEmpty())
        assertEquals(listOf("monthly"), monetization.purchaseCalls)
    }
}
