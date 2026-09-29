package id.hanifalfaqih.aienglishinterview.feature.feedback

import id.hanifalfaqih.aienglishinterview.data.remote.CreateConversationRequest
import id.hanifalfaqih.aienglishinterview.data.remote.CreateConversationResponse
import id.hanifalfaqih.aienglishinterview.data.remote.ConversationStateDto
import id.hanifalfaqih.aienglishinterview.data.remote.CreateExperienceProfileRequest
import id.hanifalfaqih.aienglishinterview.data.remote.CreateExperienceProfileResponse
import id.hanifalfaqih.aienglishinterview.data.remote.FeedbackDto
import id.hanifalfaqih.aienglishinterview.data.remote.GetConversationResponse
import id.hanifalfaqih.aienglishinterview.data.remote.OpeningResponse
import id.hanifalfaqih.aienglishinterview.data.remote.SubmitRetryRequestDto
import id.hanifalfaqih.aienglishinterview.data.remote.RetryResponseDto
import id.hanifalfaqih.aienglishinterview.data.remote.RetryFeedbackDto
import id.hanifalfaqih.aienglishinterview.data.model.AnswerFeedback
import id.hanifalfaqih.aienglishinterview.data.remote.InterviewApi
import id.hanifalfaqih.aienglishinterview.data.remote.ResumeParseResponse
import id.hanifalfaqih.aienglishinterview.data.remote.SendTurnRequest
import id.hanifalfaqih.aienglishinterview.data.remote.SendTurnResponse
import id.hanifalfaqih.aienglishinterview.data.remote.VoiceTurnResponse
import id.hanifalfaqih.aienglishinterview.core.monetization.FakeMonetization
import id.hanifalfaqih.aienglishinterview.data.repository.FeedbackRepository
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

private fun sampleDto() = FeedbackDto(
    conversationId = "conv-1",
    promptVersion = "v1",
    overall = "Great clarity.",
    answerItems = emptyList(),
    professionalCommunication = listOf("Confident tone."),
    createdAt = "2026-09-28T00:00:00.000Z",
)

private class ScriptedFeedbackApi(
    var next: () -> Response<FeedbackDto> = { Response.success(sampleDto()) },
    var conversationDetail: GetConversationResponse = GetConversationResponse(
        id = "conv-1",
        status = "closed",
        state = ConversationStateDto("wrap_up", emptyList(), null, 8),
        experienceProfileId = "profile-9",
    ),
    var newConversationId: String = "conv-2",
) : InterviewApi {
    var calls = 0
    var practiceCalls = 0
    var lastProfileId: String? = null
    override suspend fun createExperienceProfile(body: CreateExperienceProfileRequest): CreateExperienceProfileResponse =
        throw UnsupportedOperationException()
    override suspend fun createConversation(body: CreateConversationRequest): CreateConversationResponse {
        practiceCalls++
        lastProfileId = body.experienceProfileId
        return CreateConversationResponse(
            newConversationId, "active", ConversationStateDto("intro", emptyList(), null, 0),
        )
    }
    override suspend fun sendTurn(conversationId: String, body: SendTurnRequest): SendTurnResponse =
        throw UnsupportedOperationException()
    override suspend fun getOpening(conversationId: String): OpeningResponse =
        throw UnsupportedOperationException()

    override suspend fun submitRetry(
        conversationId: String,
        body: SubmitRetryRequestDto,
    ): retrofit2.Response<RetryResponseDto> {
        submitCalls++
        lastRetryKey = body.retryClientKey
        return submitRetryScript(conversationId, body)
    }

    var submitRetryScript: suspend (String, SubmitRetryRequestDto) -> retrofit2.Response<RetryResponseDto> =
        { _, _ -> throw UnsupportedOperationException() }
    var submitCalls = 0
    var lastRetryKey: String? = null

    override suspend fun getCurrentRetry(
        conversationId: String,
        answerMessageId: String,
    ): RetryResponseDto = getCurrentRetryScript(conversationId, answerMessageId)

    var getCurrentRetryScript: suspend (String, String) -> RetryResponseDto =
        { _, _ -> throw UnsupportedOperationException() }

    override suspend fun regenerateRetryFeedback(
        conversationId: String,
        answerMessageId: String,
    ): RetryResponseDto = regenerateScript(conversationId, answerMessageId)

    var regenerateScript: suspend (String, String) -> RetryResponseDto =
        { _, _ -> throw UnsupportedOperationException() }


    override suspend fun getConversation(conversationId: String): GetConversationResponse =
        conversationDetail
    override suspend fun generateFeedback(conversationId: String): Response<FeedbackDto> {
        calls++
        return next()
    }
    override suspend fun getFeedback(conversationId: String): FeedbackDto =
        throw UnsupportedOperationException()
    override suspend fun parseResume(file: okhttp3.MultipartBody.Part): ResumeParseResponse =
        throw UnsupportedOperationException()
    override suspend fun sendVoiceTurn(
        conversationId: String,
        audio: okhttp3.MultipartBody.Part,
        clientTurnId: okhttp3.RequestBody,
    ): VoiceTurnResponse =
        throw UnsupportedOperationException()
}

@OptIn(ExperimentalCoroutinesApi::class)
class FeedbackViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun loadsContentOnStart() = runTest(dispatcher) {
        val vm = FeedbackViewModel("conv-1", FeedbackRepository(ScriptedFeedbackApi()))
        advanceUntilIdle()
        val state = vm.uiState as FeedbackUiState.Content
        assertEquals("Great clarity.", state.feedback.overall)
        assertEquals("conv-1", vm.conversationId)
    }

    @Test
    fun errorThenRetrySucceeds() = runTest(dispatcher) {
        val api = ScriptedFeedbackApi(next = { throw IOException("down") })
        val vm = FeedbackViewModel("conv-1", FeedbackRepository(api))
        advanceUntilIdle()
        val error = vm.uiState as FeedbackUiState.Error
        assertTrue(error.message.isNotBlank())

        api.next = { Response.success(sampleDto()) }
        vm.retry()
        advanceUntilIdle()
        val state = vm.uiState as FeedbackUiState.Content
        assertEquals(listOf("Confident tone."), state.feedback.professionalCommunication)
        assertEquals(2, api.calls)
    }

    @Test
    fun httpErrorSurfaced() = runTest(dispatcher) {
        val api = ScriptedFeedbackApi(
            next = {
                Response.error(
                    409,
                    "closed".toResponseBody("text/plain".toMediaType()),
                )
            },
        )
        val vm = FeedbackViewModel("conv-1", FeedbackRepository(api))
        advanceUntilIdle()
        assertTrue(vm.uiState is FeedbackUiState.Error)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PracticeAgainTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(
        api: ScriptedFeedbackApi,
        monetization: FakeMonetization = FakeMonetization(isPremium = true),
    ) = FeedbackViewModel(
        "conv-1",
        FeedbackRepository(api),
        id.hanifalfaqih.aienglishinterview.data.repository.ConversationRepository(api),
        monetization,
    )

    @Test
    fun practiceAgain_createsNewConversationOnSameProfile() = runTest(dispatcher) {
        val api = ScriptedFeedbackApi()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.practiceAgain()
        advanceUntilIdle()

        val done = vm.practiceAgainState as PracticeAgainState.Done
        assertEquals("conv-2", done.conversationId)
        assertEquals("profile-9", api.lastProfileId)
        assertEquals(1, api.practiceCalls)
    }

    @Test
    fun practiceAgain_missingProfileIsErrorWithoutNewSession() = runTest(dispatcher) {
        val api = ScriptedFeedbackApi(
            conversationDetail = GetConversationResponse(
                id = "conv-1",
                status = "closed",
                state = ConversationStateDto("wrap_up", emptyList(), null, 8),
                experienceProfileId = null,
            ),
        )
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.practiceAgain()
        advanceUntilIdle()

        assertTrue(vm.practiceAgainState is PracticeAgainState.Error)
        assertEquals(0, api.practiceCalls)
    }

    @Test
    fun practiceAgain_lookupFailureIsError() = runTest(dispatcher) {
        val api = ScriptedFeedbackApi()
        val failing = object : InterviewApi by api {
            override suspend fun getConversation(conversationId: String): GetConversationResponse {
                throw IOException("down")
            }
        }
        val vm = FeedbackViewModel(
            "conv-1",
            FeedbackRepository(failing),
            id.hanifalfaqih.aienglishinterview.data.repository.ConversationRepository(failing),
            FakeMonetization(isPremium = true),
        )
        advanceUntilIdle()

        vm.practiceAgain()
        advanceUntilIdle()

        assertTrue(vm.practiceAgainState is PracticeAgainState.Error)
        assertEquals(0, api.practiceCalls)
    }

    @Test
    fun practiceAgain_duplicateTapsCreateOneSession() = runTest(dispatcher) {
        val api = ScriptedFeedbackApi()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.practiceAgain()
        vm.practiceAgain()
        advanceUntilIdle()

        assertEquals(1, api.practiceCalls)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PracticeAgainGatingTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(
        api: ScriptedFeedbackApi,
        monetization: FakeMonetization,
    ) = FeedbackViewModel(
        "conv-1",
        FeedbackRepository(api),
        id.hanifalfaqih.aienglishinterview.data.repository.ConversationRepository(api),
        monetization,
    )

    @Test
    fun nonPremium_practiceAgainRequiresPurchaseWithoutConversation() = runTest(dispatcher) {
        val api = ScriptedFeedbackApi()
        val monetization = FakeMonetization(isPremium = false)
        val vm = viewModel(api, monetization)
        advanceUntilIdle()

        vm.practiceAgain()
        advanceUntilIdle()

        assertTrue(vm.practiceAgainState is PracticeAgainState.RequiresPurchase)
        assertEquals(0, api.practiceCalls)
        assertTrue(monetization.refreshCalls >= 1)
    }

    @Test
    fun paywallEvent_consumedAfterNavigation() = runTest(dispatcher) {
        val api = ScriptedFeedbackApi()
        val vm = viewModel(api, FakeMonetization(isPremium = false))
        advanceUntilIdle()

        vm.practiceAgain()
        advanceUntilIdle()
        vm.onPaywallNavigated()

        assertTrue(vm.practiceAgainState is PracticeAgainState.Idle)
        assertEquals(0, api.practiceCalls)
    }

    @Test
    fun premiumAfterPurchase_practiceAgainProceeds() = runTest(dispatcher) {
        val api = ScriptedFeedbackApi()
        val monetization = FakeMonetization(isPremium = false)
        val vm = viewModel(api, monetization)
        advanceUntilIdle()

        vm.practiceAgain()
        advanceUntilIdle()
        assertTrue(vm.practiceAgainState is PracticeAgainState.RequiresPurchase)
        vm.onPaywallNavigated()

        // Successful purchase flips entitlement; the user taps again.
        monetization.setPremium(true)
        vm.practiceAgain()
        advanceUntilIdle()

        val done = vm.practiceAgainState as PracticeAgainState.Done
        assertEquals("conv-2", done.conversationId)
        assertEquals(1, api.practiceCalls)
    }

    @Test
    fun premium_practiceAgainSkipsPaywall() = runTest(dispatcher) {
        val api = ScriptedFeedbackApi()
        val vm = viewModel(api, FakeMonetization(isPremium = true))
        advanceUntilIdle()

        vm.practiceAgain()
        advanceUntilIdle()

        assertTrue(vm.practiceAgainState is PracticeAgainState.Done)
        assertEquals(1, api.practiceCalls)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AnswerRetryTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun retryDto(
        feedback: RetryFeedbackDto? = RetryFeedbackDto(
            overall = "Clearer now.",
            whatWorked = null,
            couldImprove = null,
            tryNextTime = null,
            professionalCommunication = null,
        ),
        status: String = "generated",
    ) = RetryResponseDto(
        id = "r1",
        conversationId = "conv-1",
        answerMessageId = "u1",
        questionMessageId = "a1",
        retryClientKey = "key-1",
        retryAnswer = "Better answer.",
        feedback = feedback,
        feedbackStatus = status,
        feedbackPromptVersion = "retry-feedback-1.0.0",
        originalQuestion = "Tell me about a challenge?",
        originalAnswer = "Compression was hard.",
        createdAt = "2026-09-29T00:00:00.000Z",
        updatedAt = "2026-09-29T00:00:00.000Z",
    )

    private fun item(practice: Boolean = true) = AnswerFeedback(
        answerMessageId = "u1",
        questionMessageId = "a1",
        questionText = "Tell me about a challenge?",
        whatWorked = null,
        couldImprove = "Explain first.",
        tryNextTime = "Use STAR.",
        practiceOpportunity = practice,
    )

    private fun viewModel(api: ScriptedFeedbackApi) = FeedbackViewModel(
        "conv-1",
        FeedbackRepository(api),
        id.hanifalfaqih.aienglishinterview.data.repository.ConversationRepository(api),
        FakeMonetization(isPremium = true),
        id.hanifalfaqih.aienglishinterview.data.repository.RetryRepository(api),
    )

    private fun httpError(code: Int): retrofit2.HttpException =
        retrofit2.HttpException(Response.error<Any>(code, "".toResponseBody()))

    @Test
    fun practiceTrue_allowsSubmission() = runTest(dispatcher) {
        val api = ScriptedFeedbackApi()
        api.submitRetryScript = { _, _ -> Response.success(201, retryDto()) }
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.startRetry(item(practice = true), "Better answer.")
        advanceUntilIdle()

        val state = vm.retryStates["u1"] as AnswerRetryState.FeedbackAvailable
        assertEquals("r1", state.retry.id)
        assertEquals("Clearer now.", state.retry.feedback?.overall)
        assertEquals(1, api.submitCalls)
    }

    @Test
    fun practiceFalse_doesNotSubmit() = runTest(dispatcher) {
        val api = ScriptedFeedbackApi()
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.startRetry(item(practice = false), "Better answer.")
        advanceUntilIdle()

        assertTrue(vm.retryStates["u1"] is AnswerRetryState.Error)
        assertEquals(0, api.submitCalls)
    }

    @Test
    fun submitting_enteredBeforeRepositoryCall() = runTest(dispatcher) {
        val gate = kotlinx.coroutines.CompletableDeferred<Response<RetryResponseDto>>()
        val api = ScriptedFeedbackApi()
        api.submitRetryScript = { _, _ -> gate.await() }
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.startRetry(item(), "Better answer.")
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.retryStates["u1"] is AnswerRetryState.Submitting)

        gate.complete(Response.success(201, retryDto()))
        advanceUntilIdle()
        assertTrue(vm.retryStates["u1"] is AnswerRetryState.FeedbackAvailable)
    }

    @Test
    fun http200_mapsToSameAvailableSemantics() = runTest(dispatcher) {
        val api = ScriptedFeedbackApi()
        api.submitRetryScript = { _, _ -> Response.success(200, retryDto()) }
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.startRetry(item(), "Better answer.")
        advanceUntilIdle()

        val state = vm.retryStates["u1"] as AnswerRetryState.FeedbackAvailable
        assertEquals("r1", state.retry.id)
    }

    @Test
    fun failedStatus_mapsToFeedbackFailed() = runTest(dispatcher) {
        val api = ScriptedFeedbackApi()
        api.submitRetryScript = { _, _ -> Response.success(201, retryDto(feedback = null, status = "failed")) }
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.startRetry(item(), "Better answer.")
        advanceUntilIdle()

        val state = vm.retryStates["u1"] as AnswerRetryState.FeedbackFailed
        assertEquals("r1", state.retry.id)
        assertNull(state.retry.feedback)
    }

    @Test
    fun generatedStatusWithNullFeedback_isNotManufacturedSuccess() = runTest(dispatcher) {
        val api = ScriptedFeedbackApi()
        api.submitRetryScript = { _, _ -> Response.success(201, retryDto(feedback = null, status = "generated")) }
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.startRetry(item(), "Better answer.")
        advanceUntilIdle()

        assertTrue(vm.retryStates["u1"] is AnswerRetryState.FeedbackFailed)
    }

    @Test
    fun originalFeedback_unchangedAfterRetry() = runTest(dispatcher) {
        val api = ScriptedFeedbackApi()
        api.submitRetryScript = { _, _ -> Response.success(201, retryDto()) }
        val vm = viewModel(api)
        advanceUntilIdle()
        val before = (vm.uiState as FeedbackUiState.Content).feedback

        vm.startRetry(item(), "Better answer.")
        advanceUntilIdle()

        val after = (vm.uiState as FeedbackUiState.Content).feedback
        assertEquals(before, after)
    }

    @Test
    fun retryKey_generatedOncePerOperation() = runTest(dispatcher) {
        val api = ScriptedFeedbackApi()
        api.submitRetryScript = { _, _ -> Response.success(201, retryDto()) }
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.startRetry(item(), "Better answer.")
        advanceUntilIdle()

        val key = api.lastRetryKey
        assertTrue(!key.isNullOrBlank())
        assertTrue(key != "u1" && key != "conv-1")
    }

    @Test
    fun repeatedActionWhileSubmitting_noDuplicateRequest() = runTest(dispatcher) {
        val gate = kotlinx.coroutines.CompletableDeferred<Response<RetryResponseDto>>()
        val api = ScriptedFeedbackApi()
        api.submitRetryScript = { _, _ -> gate.await() }
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.startRetry(item(), "Better answer.")
        vm.startRetry(item(), "Better answer.")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, api.submitCalls)

        gate.complete(Response.success(201, retryDto()))
        advanceUntilIdle()
        assertEquals(1, api.submitCalls)
    }

    @Test
    fun error404_handled() = runTest(dispatcher) {
        val api = ScriptedFeedbackApi()
        api.submitRetryScript = { _, _ -> throw httpError(404) }
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.startRetry(item(), "Better answer.")
        advanceUntilIdle()

        val state = vm.retryStates["u1"] as AnswerRetryState.Error
        assertEquals(404, state.httpCode)
    }

    @Test
    fun error409_distinguishable() = runTest(dispatcher) {
        val api = ScriptedFeedbackApi()
        api.submitRetryScript = { _, _ -> throw httpError(409) }
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.startRetry(item(), "Better answer.")
        advanceUntilIdle()

        val state = vm.retryStates["u1"] as AnswerRetryState.Error
        assertEquals(409, state.httpCode)
        assertTrue(state.message.isNotBlank())
    }

    @Test
    fun loadCurrentRetry_404meansNoRetry() = runTest(dispatcher) {
        val api = ScriptedFeedbackApi()
        api.getCurrentRetryScript = { _, _ -> throw httpError(404) }
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.loadCurrentRetry("u1")
        advanceUntilIdle()

        assertTrue(vm.retryStates["u1"] is AnswerRetryState.Idle)
    }

    @Test
    fun loadCurrentRetry_populatesOn200() = runTest(dispatcher) {
        val api = ScriptedFeedbackApi()
        api.getCurrentRetryScript = { _, _ -> retryDto() }
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.loadCurrentRetry("u1")
        advanceUntilIdle()

        val state = vm.retryStates["u1"] as AnswerRetryState.FeedbackAvailable
        assertEquals("r1", state.retry.id)
        assertEquals("Tell me about a challenge?", state.retry.originalQuestion)
    }

    @Test
    fun regenerate_updatesState() = runTest(dispatcher) {
        val api = ScriptedFeedbackApi()
        api.regenerateScript = { _, _ -> retryDto() }
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.regenerateRetry("u1")
        advanceUntilIdle()

        val state = vm.retryStates["u1"] as AnswerRetryState.FeedbackAvailable
        assertEquals("r1", state.retry.id)
    }

    @Test
    fun regenerate_failureKeepsM11Intact() = runTest(dispatcher) {
        val api = ScriptedFeedbackApi()
        api.regenerateScript = { _, _ -> throw httpError(502) }
        val vm = viewModel(api)
        advanceUntilIdle()
        val before = (vm.uiState as FeedbackUiState.Content).feedback

        vm.regenerateRetry("u1")
        advanceUntilIdle()

        val state = vm.retryStates["u1"] as AnswerRetryState.Error
        assertEquals(502, state.httpCode)
        assertEquals(before, (vm.uiState as FeedbackUiState.Content).feedback)
    }

    @Test
    fun states_keyedPerAnswer() = runTest(dispatcher) {
        val api = ScriptedFeedbackApi()
        api.submitRetryScript = { _, _ -> Response.success(201, retryDto()) }
        val vm = viewModel(api)
        advanceUntilIdle()

        vm.startRetry(item(), "Better answer.")
        advanceUntilIdle()

        assertTrue(vm.retryStates["u1"] is AnswerRetryState.FeedbackAvailable)
        assertNull(vm.retryStates["u2"])
    }
}

class RetryAffordanceTest {

    private fun item(practice: Boolean) = AnswerFeedback(
        answerMessageId = "u1",
        questionMessageId = "a1",
        questionText = "Q?",
        whatWorked = null,
        couldImprove = "Explain first.",
        tryNextTime = null,
        practiceOpportunity = practice,
    )

    @Test
    fun practiceTrue_showsAffordance() {
        assertTrue(item(practice = true).shouldShowRetryAffordance())
    }

    @Test
    fun practiceFalse_hidesAffordance() {
        assertTrue(!item(practice = false).shouldShowRetryAffordance())
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class M12MonetizationGateTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun retryDto() = RetryResponseDto(
        id = "r1",
        conversationId = "conv-1",
        answerMessageId = "u1",
        questionMessageId = "a1",
        retryClientKey = "key-1",
        retryAnswer = "Better answer.",
        feedback = RetryFeedbackDto(overall = "Clearer.", professionalCommunication = null),
        feedbackStatus = "generated",
        feedbackPromptVersion = "retry-feedback-1.0.0",
        originalQuestion = "Q?",
        originalAnswer = "A.",
        createdAt = "",
        updatedAt = "",
    )

    private fun item(practice: Boolean = true) = AnswerFeedback(
        answerMessageId = "u1",
        questionMessageId = "a1",
        questionText = "Q?",
        whatWorked = null,
        couldImprove = "Explain first.",
        tryNextTime = null,
        practiceOpportunity = practice,
    )

    private fun viewModel(api: ScriptedFeedbackApi, monetization: FakeMonetization) =
        FeedbackViewModel(
            "conv-1",
            FeedbackRepository(api),
            id.hanifalfaqih.aienglishinterview.data.repository.ConversationRepository(api),
            monetization,
            id.hanifalfaqih.aienglishinterview.data.repository.RetryRepository(api),
        )

    @Test
    fun ineligibleItem_neverTouchesMonetizationOrRetry() = runTest(dispatcher) {
        val api = ScriptedFeedbackApi()
        val monetization = FakeMonetization(isPremium = false)
        val vm = viewModel(api, monetization)
        advanceUntilIdle()
        val refreshesBefore = monetization.refreshCalls

        vm.requestPractice(item(practice = false))
        advanceUntilIdle()

        assertEquals(refreshesBefore, monetization.refreshCalls)
        assertEquals(false, vm.retryGateRequiresPurchase)
        assertEquals(null, vm.practiceFormTarget)
        assertEquals(0, api.submitCalls)
    }

    @Test
    fun eligibleNonPremium_routesToPaywallWithoutRetryPost() = runTest(dispatcher) {
        val api = ScriptedFeedbackApi()
        api.submitRetryScript = { _, _ -> Response.success(201, retryDto()) }
        val monetization = FakeMonetization(isPremium = false)
        val vm = viewModel(api, monetization)
        advanceUntilIdle()

        vm.requestPractice(item())
        advanceUntilIdle()

        assertTrue(vm.retryGateRequiresPurchase)
        assertEquals(null, vm.practiceFormTarget)
        assertEquals(0, api.submitCalls)

        vm.onRetryGateNavigated()
        assertEquals(false, vm.retryGateRequiresPurchase)
    }

    @Test
    fun eligiblePremium_opensFormWithoutPaywall() = runTest(dispatcher) {
        val api = ScriptedFeedbackApi()
        api.submitRetryScript = { _, _ -> Response.success(201, retryDto()) }
        val monetization = FakeMonetization(isPremium = true)
        val vm = viewModel(api, monetization)
        advanceUntilIdle()

        vm.requestPractice(item())
        advanceUntilIdle()

        assertEquals("u1", vm.practiceFormTarget)
        assertEquals(false, vm.retryGateRequiresPurchase)
        assertEquals(0, api.submitCalls)
    }

    @Test
    fun startRetry_nonPremium_neverCallsRetryBackend() = runTest(dispatcher) {
        val api = ScriptedFeedbackApi()
        api.submitRetryScript = { _, _ -> Response.success(201, retryDto()) }
        val monetization = FakeMonetization(isPremium = false)
        val vm = viewModel(api, monetization)
        advanceUntilIdle()

        vm.startRetry(item(), "Better answer.")
        advanceUntilIdle()

        assertEquals(0, api.submitCalls)
        assertTrue(vm.retryGateRequiresPurchase)
        assertTrue(vm.retryStates["u1"] == null || vm.retryStates["u1"] is AnswerRetryState.Idle)
    }

    @Test
    fun startRetry_premium_callsRetryBackend() = runTest(dispatcher) {
        val api = ScriptedFeedbackApi()
        api.submitRetryScript = { _, _ -> Response.success(201, retryDto()) }
        val monetization = FakeMonetization(isPremium = true)
        val vm = viewModel(api, monetization)
        advanceUntilIdle()

        vm.startRetry(item(), "Better answer.")
        advanceUntilIdle()

        assertEquals(1, api.submitCalls)
        assertTrue(vm.retryStates["u1"] is AnswerRetryState.FeedbackAvailable)
        assertEquals(null, vm.practiceFormTarget)
    }

    @Test
    fun closePracticeForm_clearsTarget() = runTest(dispatcher) {
        val api = ScriptedFeedbackApi()
        val monetization = FakeMonetization(isPremium = true)
        val vm = viewModel(api, monetization)
        advanceUntilIdle()

        vm.requestPractice(item())
        advanceUntilIdle()
        assertEquals("u1", vm.practiceFormTarget)

        vm.closePracticeForm()
        assertEquals(null, vm.practiceFormTarget)
    }
}
