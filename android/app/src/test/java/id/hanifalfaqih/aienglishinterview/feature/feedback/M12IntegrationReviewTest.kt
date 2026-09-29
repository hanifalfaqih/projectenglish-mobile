package id.hanifalfaqih.aienglishinterview.feature.feedback

import id.hanifalfaqih.aienglishinterview.core.monetization.FakeMonetization
import id.hanifalfaqih.aienglishinterview.data.model.AnswerFeedback
import id.hanifalfaqih.aienglishinterview.data.remote.ConversationStateDto
import id.hanifalfaqih.aienglishinterview.data.remote.CreateConversationRequest
import id.hanifalfaqih.aienglishinterview.data.remote.CreateConversationResponse
import id.hanifalfaqih.aienglishinterview.data.remote.CreateExperienceProfileRequest
import id.hanifalfaqih.aienglishinterview.data.remote.CreateExperienceProfileResponse
import id.hanifalfaqih.aienglishinterview.data.remote.FeedbackDto
import id.hanifalfaqih.aienglishinterview.data.remote.AnswerFeedbackItemDto
import id.hanifalfaqih.aienglishinterview.data.remote.GetConversationResponse
import id.hanifalfaqih.aienglishinterview.data.remote.OpeningResponse
import id.hanifalfaqih.aienglishinterview.data.remote.ResumeParseResponse
import id.hanifalfaqih.aienglishinterview.data.remote.RetryFeedbackDto
import id.hanifalfaqih.aienglishinterview.data.remote.RetryResponseDto
import id.hanifalfaqih.aienglishinterview.data.remote.InterviewApi
import id.hanifalfaqih.aienglishinterview.data.remote.SendTurnRequest
import id.hanifalfaqih.aienglishinterview.data.remote.SendTurnResponse
import id.hanifalfaqih.aienglishinterview.data.remote.SubmitRetryRequestDto
import id.hanifalfaqih.aienglishinterview.data.remote.VoiceTurnResponse
import id.hanifalfaqih.aienglishinterview.data.repository.ConversationRepository
import id.hanifalfaqih.aienglishinterview.data.repository.FeedbackRepository
import id.hanifalfaqih.aienglishinterview.data.repository.RetryRepository
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import retrofit2.HttpException
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * M12 integrated review: production FeedbackViewModel + RetryRepository +
 * FeedbackRepository driven through an injected InterviewApi double and
 * FakeMonetization. No real network, no backend, no device.
 */
private class M12FakeApi(
    items: List<AnswerFeedbackItemDto>,
) : InterviewApi {
    var submitScript: suspend (SubmitRetryRequestDto) -> Response<RetryResponseDto> =
        { Response.success(201, retryDto(it.answerMessageId)) }
    val submitRequests = mutableListOf<SubmitRetryRequestDto>()
    var regenerateCalls = 0
    var currentRetryCalls = 0
    var practiceCalls = 0

    val feedback = FeedbackDto(
        conversationId = "conv-1",
        promptVersion = "feedback-1.1.0",
        overall = "Overall summary.",
        answerItems = items,
        professionalCommunication = listOf("Steady pace."),
        createdAt = "2026-09-30T00:00:00.000Z",
    )

    override suspend fun createExperienceProfile(body: CreateExperienceProfileRequest): CreateExperienceProfileResponse =
        throw UnsupportedOperationException()
    override suspend fun createConversation(body: CreateConversationRequest): CreateConversationResponse {
        practiceCalls++
        return CreateConversationResponse(
            "conv-new", "active", ConversationStateDto("intro", emptyList(), null, 0),
        )
    }
    override suspend fun sendTurn(conversationId: String, body: SendTurnRequest): SendTurnResponse =
        throw UnsupportedOperationException()
    override suspend fun getOpening(conversationId: String): OpeningResponse =
        throw UnsupportedOperationException()
    override suspend fun getConversation(conversationId: String): GetConversationResponse =
        GetConversationResponse(
            id = "conv-1",
            status = "closed",
            state = ConversationStateDto("wrap_up", emptyList(), null, 8),
            experienceProfileId = "profile-9",
        )
    override suspend fun generateFeedback(conversationId: String): Response<FeedbackDto> =
        Response.success(200, feedback)
    override suspend fun getFeedback(conversationId: String): FeedbackDto = feedback
    override suspend fun parseResume(file: MultipartBody.Part): ResumeParseResponse =
        throw UnsupportedOperationException()
    override suspend fun sendVoiceTurn(
        conversationId: String,
        audio: MultipartBody.Part,
        clientTurnId: RequestBody,
    ): VoiceTurnResponse = throw UnsupportedOperationException()

    override suspend fun submitRetry(
        conversationId: String,
        body: SubmitRetryRequestDto,
    ): Response<RetryResponseDto> {
        submitRequests.add(body)
        return submitScript(body)
    }

    override suspend fun getCurrentRetry(
        conversationId: String,
        answerMessageId: String,
    ): RetryResponseDto {
        currentRetryCalls++
        throw HttpException(Response.error<Any>(404, "".toResponseBody()))
    }

    override suspend fun regenerateRetryFeedback(
        conversationId: String,
        answerMessageId: String,
    ): RetryResponseDto {
        regenerateCalls++
        return retryDto(answerMessageId)
    }

    companion object {
        fun retryDto(
            answerId: String,
            feedback: RetryFeedbackDto? = RetryFeedbackDto(
                overall = "Retry overall.",
                whatWorked = "Retry worked.",
                couldImprove = "Retry improve.",
                tryNextTime = "Retry next.",
                professionalCommunication = null,
            ),
            status: String = "generated",
        ) = RetryResponseDto(
            id = "retry-$answerId",
            conversationId = "conv-1",
            answerMessageId = answerId,
            questionMessageId = "q-$answerId",
            retryClientKey = "server-key-$answerId",
            retryAnswer = "Injected retry answer.",
            feedback = feedback,
            feedbackStatus = status,
            feedbackPromptVersion = "retry-feedback-1.0.0",
            originalQuestion = "Original question?",
            originalAnswer = "Original answer.",
            createdAt = "2026-09-30T00:00:00.000Z",
            updatedAt = "2026-09-30T00:00:01.000Z",
        )
    }
}

private fun itemDto(id: String, practice: Boolean) = AnswerFeedbackItemDto(
    answerMessageId = id,
    questionMessageId = "q-$id",
    questionText = "Question for $id?",
    whatWorked = "worked",
    couldImprove = "improve",
    tryNextTime = "next",
    practiceOpportunity = practice,
)

private fun domainItem(id: String, practice: Boolean) = AnswerFeedback(
    answerMessageId = id,
    questionMessageId = "q-$id",
    questionText = "Question for $id?",
    whatWorked = "worked",
    couldImprove = "improve",
    tryNextTime = "next",
    practiceOpportunity = practice,
)

@OptIn(ExperimentalCoroutinesApi::class)
class M12IntegrationReviewTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun stack(
        items: List<AnswerFeedbackItemDto> = listOf(itemDto("u1", true)),
        premium: Boolean = true,
    ): Triple<FeedbackViewModel, M12FakeApi, FakeMonetization> {
        val api = M12FakeApi(items)
        val monetization = FakeMonetization(isPremium = premium)
        val vm = FeedbackViewModel(
            "conv-1",
            FeedbackRepository(api),
            ConversationRepository(api),
            monetization,
            RetryRepository(api),
        )
        return Triple(vm, api, monetization)
    }

    // --- Scenario A: ineligible item ---

    @Test
    fun scenarioA_ineligible_noAffordanceNoGateNoPost() = runTest(dispatcher) {
        val (vm, api, monetization) = stack(items = listOf(itemDto("u1", false)), premium = false)
        advanceUntilIdle()
        val refreshes = monetization.refreshCalls
        val item = domainItem("u1", false)

        assertFalse(item.shouldShowRetryAffordance())
        vm.requestPractice(item)
        vm.startRetry(item, "An answer.")
        advanceUntilIdle()

        assertEquals(refreshes, monetization.refreshCalls) // no monetization touch
        assertFalse(vm.retryGateRequiresPurchase) // no paywall event
        assertNull(vm.practiceFormTarget) // no form
        assertEquals(0, api.submitRequests.size) // no retry POST
        assertTrue(vm.retryStates["u1"] is AnswerRetryState.Error) // safe refusal
    }

    // --- Scenario B: eligible + non-premium ---

    @Test
    fun scenarioB_eligibleNonPremium_paywallOnceNoPost() = runTest(dispatcher) {
        val (vm, api, monetization) = stack(premium = false)
        advanceUntilIdle()
        val item = domainItem("u1", true)
        assertTrue(item.shouldShowRetryAffordance())

        vm.requestPractice(item)
        advanceUntilIdle()

        assertTrue(monetization.refreshCalls >= 1) // existing premium mechanism used
        assertTrue(vm.retryGateRequiresPurchase) // paywall event emitted
        assertNull(vm.practiceFormTarget) // form NOT available pre-purchase
        assertEquals(0, api.submitRequests.size) // no retry POST

        vm.onRetryGateNavigated()
        assertFalse(vm.retryGateRequiresPurchase) // one-shot consumed exactly once
        assertEquals(0, api.submitRequests.size)
    }

    // --- Scenario C: eligible + premium + 201 ---

    @Test
    fun scenarioC_premium_successFullContract() = runTest(dispatcher) {
        val (vm, api, _) = stack(premium = true)
        advanceUntilIdle()
        val before = (vm.uiState as FeedbackUiState.Content).feedback
        val item = domainItem("u1", true)

        vm.requestPractice(item)
        advanceUntilIdle()
        assertEquals("u1", vm.practiceFormTarget) // form available
        assertFalse(vm.retryGateRequiresPurchase) // no paywall

        vm.startRetry(item, "Injected retry answer.")
        advanceUntilIdle()

        assertEquals(1, api.submitRequests.size)
        val req = api.submitRequests.single()
        assertEquals("u1", req.answerMessageId)
        assertEquals("q-u1", req.questionMessageId)
        assertEquals("Injected retry answer.", req.retryAnswer)
        assertTrue(req.retryClientKey.isNotBlank())
        assertTrue(req.retryClientKey != req.answerMessageId)
        assertTrue(req.retryClientKey != req.questionMessageId)
        assertTrue(req.retryClientKey != "conv-1")
        assertTrue(Regex("[0-9a-f-]{36}").matches(req.retryClientKey)) // fresh UUID

        val state = vm.retryStates["u1"] as AnswerRetryState.FeedbackAvailable
        val retry = state.retry
        assertEquals("retry-u1", retry.id)
        assertEquals("conv-1", retry.conversationId)
        assertEquals("u1", retry.answerMessageId)
        assertEquals("q-u1", retry.questionMessageId)
        assertEquals("server-key-u1", retry.retryClientKey)
        assertEquals("Injected retry answer.", retry.retryAnswer)
        assertEquals("generated", retry.feedbackStatus)
        assertEquals("retry-feedback-1.0.0", retry.feedbackPromptVersion)
        assertEquals("Original question?", retry.originalQuestion)
        assertEquals("Original answer.", retry.originalAnswer)
        assertEquals("2026-09-30T00:00:00.000Z", retry.createdAt)
        assertEquals("2026-09-30T00:00:01.000Z", retry.updatedAt)
        assertEquals("Retry overall.", retry.feedback?.overall)
        assertEquals(before, (vm.uiState as FeedbackUiState.Content).feedback) // immutable
        assertNull(vm.practiceFormTarget) // form collapsed after success
    }

    @Test
    fun scenarioC_retryFeedbackModelHasNoNumericFields() {
        val requestDescriptor = SubmitRetryRequestDto.serializer().descriptor
        val requestNames = (0 until requestDescriptor.elementsCount).map { requestDescriptor.getElementName(it) }
        assertEquals(
            listOf("answerMessageId", "questionMessageId", "retryAnswer", "retryClientKey"),
            requestNames,
        )
        val descriptor = RetryFeedbackDto.serializer().descriptor
        val names = (0 until descriptor.elementsCount).map { descriptor.getElementName(it) }
        assertEquals(
            listOf("overall", "whatWorked", "couldImprove", "tryNextTime", "professionalCommunication"),
            names,
        )
        val retryDescriptor = RetryResponseDto.serializer().descriptor
        val retryNames = (0 until retryDescriptor.elementsCount).map { retryDescriptor.getElementName(it) }
        assertEquals(
            listOf(
                "id", "conversationId", "answerMessageId", "questionMessageId",
                "retryClientKey", "retryAnswer", "feedback", "feedbackStatus",
                "feedbackPromptVersion", "originalQuestion", "originalAnswer",
                "createdAt", "updatedAt",
            ),
            retryNames,
        )
    }

    // --- Scenario D: 200 idempotent replay ---

    @Test
    fun scenarioD_http200_sameSemanticsSingleRequest() = runTest(dispatcher) {
        val (vm, api, _) = stack(premium = true)
        advanceUntilIdle()
        api.submitScript = { Response.success(200, M12FakeApi.retryDto(it.answerMessageId)) }

        vm.startRetry(domainItem("u1", true), "Injected retry answer.")
        advanceUntilIdle()

        assertEquals(1, api.submitRequests.size)
        assertTrue(vm.retryStates["u1"] is AnswerRetryState.FeedbackAvailable)
    }

    // --- Scenario E: feedback generation failed ---

    @Test
    fun scenarioE_failedStatus_noFabricatedFeedback_regenerateExposed() = runTest(dispatcher) {
        val (vm, api, _) = stack(premium = true)
        advanceUntilIdle()
        val before = (vm.uiState as FeedbackUiState.Content).feedback
        api.submitScript = {
            Response.success(
                201,
                M12FakeApi.retryDto(it.answerMessageId, feedback = null, status = "failed"),
            )
        }

        vm.startRetry(domainItem("u1", true), "Injected retry answer.")
        advanceUntilIdle()

        val state = vm.retryStates["u1"] as AnswerRetryState.FeedbackFailed
        assertEquals("Injected retry answer.", state.retry.retryAnswer) // answer intact
        assertNull(state.retry.feedback) // nothing fabricated
        assertEquals(before, (vm.uiState as FeedbackUiState.Content).feedback)

        vm.regenerateRetry("u1")
        advanceUntilIdle()
        assertEquals(1, api.regenerateCalls) // regeneration path exists
        assertTrue(vm.retryStates["u1"] is AnswerRetryState.FeedbackAvailable)
    }

    // --- Scenario F: API errors ---

    @Test
    fun scenarioF_errorsPreserveSemanticsAndRecover() = runTest(dispatcher) {
        val (vm, api, _) = stack(premium = true)
        advanceUntilIdle()
        val before = (vm.uiState as FeedbackUiState.Content).feedback

        api.submitScript = { Response.error(409, "".toResponseBody()) }
        vm.startRetry(domainItem("u1", true), "Answer.")
        advanceUntilIdle()
        val conflict = vm.retryStates["u1"] as AnswerRetryState.Error
        assertEquals(409, conflict.httpCode)

        api.submitScript = { Response.error(500, "".toResponseBody()) }
        vm.startRetry(domainItem("u1", true), "Answer.")
        advanceUntilIdle()
        val server = vm.retryStates["u1"] as AnswerRetryState.Error
        assertEquals(500, server.httpCode)
        assertEquals(before, (vm.uiState as FeedbackUiState.Content).feedback)

        api.submitScript = { Response.success(201, M12FakeApi.retryDto(it.answerMessageId)) }
        vm.startRetry(domainItem("u1", true), "Answer.")
        advanceUntilIdle()
        assertTrue(vm.retryStates["u1"] is AnswerRetryState.FeedbackAvailable) // recovery
    }

    // --- Scenario G: multiple items, keyed state ---

    @Test
    fun scenarioG_independentPerItemStates() = runTest(dispatcher) {
        val (vm, api, _) = stack(
            items = listOf(itemDto("u1", true), itemDto("u2", true), itemDto("u3", false)),
            premium = true,
        )
        advanceUntilIdle()

        vm.startRetry(domainItem("u1", true), "Answer A.")
        advanceUntilIdle()
        assertTrue(vm.retryStates["u1"] is AnswerRetryState.FeedbackAvailable)
        assertNull(vm.retryStates["u2"])
        assertNull(vm.retryStates["u3"])

        vm.startRetry(domainItem("u2", true), "Answer B.")
        advanceUntilIdle()
        assertTrue(vm.retryStates["u2"] is AnswerRetryState.FeedbackAvailable)
        assertTrue(vm.retryStates["u1"] is AnswerRetryState.FeedbackAvailable)

        assertEquals(listOf("u1", "u2"), api.submitRequests.map { it.answerMessageId })
        assertEquals(listOf("q-u1", "q-u2"), api.submitRequests.map { it.questionMessageId })
        assertTrue(api.submitRequests[0].retryClientKey != api.submitRequests[1].retryClientKey)

        vm.startRetry(domainItem("u3", false), "Answer C.")
        advanceUntilIdle()
        assertEquals(2, api.submitRequests.size) // ineligible never posts
        assertTrue(vm.retryStates["u3"] is AnswerRetryState.Error)
    }

    // --- Scenario H: M15 regression ---

    @Test
    fun scenarioH_m15SeparateFromM12() = runTest(dispatcher) {
        val (vm, api, _) = stack(items = listOf(itemDto("u1", false)), premium = true)
        advanceUntilIdle()

        vm.practiceAgain()
        advanceUntilIdle()

        assertEquals(1, api.practiceCalls) // fresh conversation flow
        assertEquals(0, api.submitRequests.size) // never touches M12 endpoint
        assertTrue(vm.practiceAgainState is PracticeAgainState.Done)
    }

    @Test
    fun scenarioH_m15NonPremiumStillGated() = runTest(dispatcher) {
        val (vm, api, _) = stack(items = listOf(itemDto("u1", false)), premium = false)
        advanceUntilIdle()

        vm.practiceAgain()
        advanceUntilIdle()

        assertTrue(vm.practiceAgainState is PracticeAgainState.RequiresPurchase)
        assertEquals(0, api.practiceCalls)
        assertEquals(0, api.submitRequests.size)
    }

    // --- Step 7: monetization truth table ---

    @Test
    fun truthTable_gate1ThenGate2() = runTest(dispatcher) {
        // false/false and false/true: gate 2 never evaluated.
        for (premium in listOf(false, true)) {
            val (vm, _, monetization) = stack(items = listOf(itemDto("u1", false)), premium = premium)
            advanceUntilIdle()
            val refreshes = monetization.refreshCalls
            vm.requestPractice(domainItem("u1", false))
            advanceUntilIdle()
            assertEquals(refreshes, monetization.refreshCalls)
            assertNull(vm.practiceFormTarget)
            assertFalse(vm.retryGateRequiresPurchase)
        }
        // true/false: paywall.
        run {
            val (vm, _, _) = stack(items = listOf(itemDto("u1", true)), premium = false)
            advanceUntilIdle()
            vm.requestPractice(domainItem("u1", true))
            advanceUntilIdle()
            assertTrue(vm.retryGateRequiresPurchase)
            assertNull(vm.practiceFormTarget)
        }
        // true/true: retry allowed.
        run {
            val (vm, _, _) = stack(items = listOf(itemDto("u1", true)), premium = true)
            advanceUntilIdle()
            vm.requestPractice(domainItem("u1", true))
            advanceUntilIdle()
            assertFalse(vm.retryGateRequiresPurchase)
            assertEquals("u1", vm.practiceFormTarget)
        }
    }

    // --- Step 5: state machine / duplicate protection ---

    @Test
    fun stateMachine_duplicateTapsSingleRequest() = runTest(dispatcher) {
        val gate = kotlinx.coroutines.CompletableDeferred<Response<RetryResponseDto>>()
        val (vm, api, _) = stack(premium = true)
        advanceUntilIdle()
        api.submitScript = { gate.await() }

        val item = domainItem("u1", true)
        vm.startRetry(item, "Answer.")
        vm.startRetry(item, "Answer.")
        vm.startRetry(item, "Answer.")
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.retryStates["u1"] is AnswerRetryState.Submitting)
        assertEquals(1, api.submitRequests.size)

        gate.complete(Response.success(201, M12FakeApi.retryDto("u1")))
        advanceUntilIdle()
        assertEquals(1, api.submitRequests.size)
        assertTrue(vm.retryStates["u1"] is AnswerRetryState.FeedbackAvailable)
    }
}
