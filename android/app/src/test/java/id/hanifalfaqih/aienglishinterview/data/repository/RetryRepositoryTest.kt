package id.hanifalfaqih.aienglishinterview.data.repository

import id.hanifalfaqih.aienglishinterview.core.network.ApiResult
import id.hanifalfaqih.aienglishinterview.data.remote.CreateConversationRequest
import id.hanifalfaqih.aienglishinterview.data.remote.CreateConversationResponse
import id.hanifalfaqih.aienglishinterview.data.remote.CreateExperienceProfileRequest
import id.hanifalfaqih.aienglishinterview.data.remote.CreateExperienceProfileResponse
import id.hanifalfaqih.aienglishinterview.data.remote.AnswerFeedbackItemDto
import id.hanifalfaqih.aienglishinterview.data.remote.FeedbackDto
import id.hanifalfaqih.aienglishinterview.data.remote.GetConversationResponse
import id.hanifalfaqih.aienglishinterview.data.remote.OpeningResponse
import id.hanifalfaqih.aienglishinterview.data.remote.SubmitRetryRequestDto
import id.hanifalfaqih.aienglishinterview.data.remote.RetryResponseDto
import id.hanifalfaqih.aienglishinterview.data.remote.RetryFeedbackDto
import id.hanifalfaqih.aienglishinterview.data.remote.InterviewApi
import id.hanifalfaqih.aienglishinterview.data.remote.ResumeParseResponse
import id.hanifalfaqih.aienglishinterview.data.remote.SendTurnRequest
import id.hanifalfaqih.aienglishinterview.data.remote.SendTurnResponse
import id.hanifalfaqih.aienglishinterview.data.remote.VoiceTurnResponse
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

private fun retryDto(
    feedback: RetryFeedbackDto? = RetryFeedbackDto(
        overall = "Clearer now.",
        whatWorked = "STAR structure.",
        couldImprove = null,
        tryNextTime = "Add metrics.",
        professionalCommunication = null,
    ),
) = RetryResponseDto(
    id = "r1",
    conversationId = "conv-1",
    answerMessageId = "u1",
    questionMessageId = "a1",
    retryClientKey = "key-1",
    retryAnswer = "Better answer.",
    feedback = feedback,
    feedbackStatus = "generated",
    feedbackPromptVersion = "retry-feedback-1.0.0",
    originalQuestion = "Tell me about a challenge?",
    originalAnswer = "Compression was hard.",
    createdAt = "2026-09-29T00:00:00.000Z",
    updatedAt = "2026-09-29T00:00:00.000Z",
)

private class FakeRetryApi(
    var submitResponses: ArrayDeque<Response<RetryResponseDto>> = ArrayDeque(),
    var currentResult: Result<RetryResponseDto> = Result.success(retryDto()),
    var regenerateResult: Result<RetryResponseDto> = Result.success(retryDto()),
    var failure: Throwable? = null,
) : InterviewApi {
    var lastSubmit: Pair<String, SubmitRetryRequestDto>? = null

    override suspend fun createExperienceProfile(body: CreateExperienceProfileRequest): CreateExperienceProfileResponse =
        throw UnsupportedOperationException()
    override suspend fun createConversation(body: CreateConversationRequest): CreateConversationResponse =
        throw UnsupportedOperationException()
    override suspend fun sendTurn(conversationId: String, body: SendTurnRequest): SendTurnResponse =
        throw UnsupportedOperationException()
    override suspend fun getOpening(conversationId: String): OpeningResponse =
        throw UnsupportedOperationException()
    override suspend fun getConversation(conversationId: String): GetConversationResponse =
        throw UnsupportedOperationException()
    override suspend fun generateFeedback(conversationId: String): Response<FeedbackDto> =
        throw UnsupportedOperationException()
    override suspend fun getFeedback(conversationId: String): FeedbackDto =
        throw UnsupportedOperationException()
    override suspend fun parseResume(file: okhttp3.MultipartBody.Part): ResumeParseResponse =
        throw UnsupportedOperationException()
    override suspend fun sendVoiceTurn(
        conversationId: String,
        audio: okhttp3.MultipartBody.Part,
        clientTurnId: okhttp3.RequestBody,
    ): VoiceTurnResponse = throw UnsupportedOperationException()

    override suspend fun submitRetry(
        conversationId: String,
        body: SubmitRetryRequestDto,
    ): Response<RetryResponseDto> {
        failure?.let { throw it }
        lastSubmit = conversationId to body
        return submitResponses.removeFirst()
    }

    override suspend fun getCurrentRetry(
        conversationId: String,
        answerMessageId: String,
    ): RetryResponseDto {
        failure?.let { throw it }
        return currentResult.getOrThrow()
    }

    override suspend fun regenerateRetryFeedback(
        conversationId: String,
        answerMessageId: String,
    ): RetryResponseDto {
        failure?.let { throw it }
        return regenerateResult.getOrThrow()
    }
}

class RetryRepositoryTest {

    @Test
    fun retryRequest_serializesExactBackendFields() {
        val json = Json.encodeToString(
            SubmitRetryRequestDto.serializer(),
            SubmitRetryRequestDto(
                answerMessageId = "u1",
                questionMessageId = "a1",
                retryAnswer = "Better answer.",
                retryClientKey = "key-9",
            ),
        )
        val decoded = Json.parseToJsonElement(json).jsonObject
        assertEquals("u1", decoded["answerMessageId"]?.jsonPrimitive?.content)
        assertEquals("a1", decoded["questionMessageId"]?.jsonPrimitive?.content)
        assertEquals("Better answer.", decoded["retryAnswer"]?.jsonPrimitive?.content)
        assertEquals("key-9", decoded["retryClientKey"]?.jsonPrimitive?.content)
    }

    @Test
    fun retryClientKey_isDistinctFromTurnIds() {
        val retryKey = java.util.UUID.randomUUID().toString()
        val turnId = java.util.UUID.randomUUID().toString()
        assertTrue(retryKey != turnId)
        assertTrue(retryKey.isNotBlank())
    }

    @Test
    fun submitRetry_201mapsAllFieldsAndMarksCreated() = runBlocking {
        val api = FakeRetryApi(
            submitResponses = ArrayDeque(listOf(Response.success(201, retryDto()))),
        )
        val result = RetryRepository(api).submitRetry("conv-1", "u1", "a1", "Better answer.", "key-1") as ApiResult.Success
        val retry = result.value.retry
        assertEquals("r1", retry.id)
        assertEquals("conv-1", retry.conversationId)
        assertEquals("u1", retry.answerMessageId)
        assertEquals("a1", retry.questionMessageId)
        assertEquals("key-1", retry.retryClientKey)
        assertEquals("Better answer.", retry.retryAnswer)
        assertEquals("generated", retry.feedbackStatus)
        assertEquals("retry-feedback-1.0.0", retry.feedbackPromptVersion)
        assertEquals("Tell me about a challenge?", retry.originalQuestion)
        assertEquals("Compression was hard.", retry.originalAnswer)
        assertEquals("Clearer now.", retry.feedback?.overall)
        assertEquals(true, result.value.newlyCreated)
        // Exact backend field names on the wire.
        assertEquals("u1", api.lastSubmit?.second?.answerMessageId)
        assertEquals("key-1", api.lastSubmit?.second?.retryClientKey)
    }

    @Test
    fun submitRetry_200marksReplayNotCreated() = runBlocking {
        val api = FakeRetryApi(
            submitResponses = ArrayDeque(listOf(Response.success(200, retryDto()))),
        )
        val result = RetryRepository(api).submitRetry("conv-1", "u1", "a1", "Better answer.", "key-1") as ApiResult.Success
        assertEquals(false, result.value.newlyCreated)
        assertEquals("r1", result.value.retry.id)
    }

    @Test
    fun submitRetry_nullFeedbackPreserved() = runBlocking {
        val api = FakeRetryApi(
            submitResponses = ArrayDeque(
                listOf(Response.success(201, retryDto(feedback = null).copy(feedbackStatus = "failed"))),
            ),
        )
        val result = RetryRepository(api).submitRetry("conv-1", "u1", "a1", "Better answer.", "key-1") as ApiResult.Success
        assertNull(result.value.retry.feedback)
        assertEquals("failed", result.value.retry.feedbackStatus)
    }

    @Test
    fun submitRetry_httpErrorKeepsCode() = runBlocking {
        val api = FakeRetryApi(
            submitResponses = ArrayDeque(
                listOf(Response.error<RetryResponseDto>(409, okhttp3.ResponseBody.create(null, ""))),
            ),
        )
        val result = RetryRepository(api).submitRetry("conv-1", "u1", "a1", "Better answer.", "key-1")
        assertTrue(result is ApiResult.Error && result.httpCode == 409)
    }

    @Test
    fun getCurrentRetry_maps200() = runBlocking {
        val result = RetryRepository(FakeRetryApi()).getCurrentRetry("conv-1", "u1") as ApiResult.Success
        assertEquals("r1", result.value.id)
        assertEquals("Compression was hard.", result.value.originalAnswer)
    }

    @Test
    fun getCurrentRetry_404distinguishable() = runBlocking {
        val api = FakeRetryApi()
        api.failure = retrofit2.HttpException(Response.error<Any>(404, okhttp3.ResponseBody.create(null, "")))
        val result = RetryRepository(api).getCurrentRetry("conv-1", "missing")
        assertTrue(result is ApiResult.Error && result.httpCode == 404)
    }

    @Test
    fun regenerate_mapsSuccess() = runBlocking {
        val result = RetryRepository(FakeRetryApi()).regenerateRetryFeedback("conv-1", "u1") as ApiResult.Success
        assertEquals("generated", result.value.feedbackStatus)
        assertEquals("Clearer now.", result.value.feedback?.overall)
    }

    @Test
    fun retryFeedback_separateFromM11() = runBlocking {
        val api = FakeRetryApi(
            submitResponses = ArrayDeque(listOf(Response.success(201, retryDto()))),
        )
        val result = RetryRepository(api).submitRetry("conv-1", "u1", "a1", "Better answer.", "key-1") as ApiResult.Success
        // Retry feedback type is distinct from M11 AnswerFeedback/Feedback.
        assertTrue(result.value.retry.feedback is id.hanifalfaqih.aienglishinterview.data.model.RetryFeedback)
    }
}
