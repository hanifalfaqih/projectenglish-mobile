package id.hanifalfaqih.aienglishinterview.feature.feedback

import id.hanifalfaqih.aienglishinterview.data.remote.CreateConversationRequest
import id.hanifalfaqih.aienglishinterview.data.remote.CreateConversationResponse
import id.hanifalfaqih.aienglishinterview.data.remote.CreateExperienceProfileRequest
import id.hanifalfaqih.aienglishinterview.data.remote.CreateExperienceProfileResponse
import id.hanifalfaqih.aienglishinterview.data.remote.FeedbackDto
import id.hanifalfaqih.aienglishinterview.data.remote.GetConversationResponse
import id.hanifalfaqih.aienglishinterview.data.remote.InterviewApi
import id.hanifalfaqih.aienglishinterview.data.remote.ResumeParseResponse
import id.hanifalfaqih.aienglishinterview.data.remote.SendTurnRequest
import id.hanifalfaqih.aienglishinterview.data.remote.SendTurnResponse
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
) : InterviewApi {
    var calls = 0
    override suspend fun createExperienceProfile(body: CreateExperienceProfileRequest): CreateExperienceProfileResponse =
        throw UnsupportedOperationException()
    override suspend fun createConversation(body: CreateConversationRequest): CreateConversationResponse =
        throw UnsupportedOperationException()
    override suspend fun sendTurn(conversationId: String, body: SendTurnRequest): SendTurnResponse =
        throw UnsupportedOperationException()
    override suspend fun getConversation(conversationId: String): GetConversationResponse =
        throw UnsupportedOperationException()
    override suspend fun generateFeedback(conversationId: String): Response<FeedbackDto> {
        calls++
        return next()
    }
    override suspend fun getFeedback(conversationId: String): FeedbackDto =
        throw UnsupportedOperationException()
    override suspend fun parseResume(file: okhttp3.MultipartBody.Part): ResumeParseResponse =
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
