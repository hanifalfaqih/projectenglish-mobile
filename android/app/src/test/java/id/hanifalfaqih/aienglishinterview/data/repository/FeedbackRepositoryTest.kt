package id.hanifalfaqih.aienglishinterview.data.repository

import id.hanifalfaqih.aienglishinterview.core.network.ApiResult
import id.hanifalfaqih.aienglishinterview.core.network.ErrorKind
import id.hanifalfaqih.aienglishinterview.data.remote.CreateConversationRequest
import id.hanifalfaqih.aienglishinterview.data.remote.CreateConversationResponse
import id.hanifalfaqih.aienglishinterview.data.remote.CreateExperienceProfileRequest
import id.hanifalfaqih.aienglishinterview.data.remote.CreateExperienceProfileResponse
import id.hanifalfaqih.aienglishinterview.data.remote.FeedbackDto
import id.hanifalfaqih.aienglishinterview.data.remote.GetConversationResponse
import id.hanifalfaqih.aienglishinterview.data.remote.OpeningResponse
import id.hanifalfaqih.aienglishinterview.data.remote.SubmitRetryRequestDto
import id.hanifalfaqih.aienglishinterview.data.remote.RetryResponseDto
import id.hanifalfaqih.aienglishinterview.data.remote.InterviewApi
import id.hanifalfaqih.aienglishinterview.data.remote.ResumeParseResponse
import id.hanifalfaqih.aienglishinterview.data.remote.SendTurnRequest
import id.hanifalfaqih.aienglishinterview.data.remote.SendTurnResponse
import id.hanifalfaqih.aienglishinterview.data.remote.VoiceTurnResponse
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

private fun feedbackDto() = FeedbackDto(
    conversationId = "conv-1",
    promptVersion = "v1",
    overall = "Solid answers overall.",
    answerItems = listOf(
        id.hanifalfaqih.aienglishinterview.data.remote.AnswerFeedbackItemDto(
            answerMessageId = "m2",
            questionMessageId = "m1",
            questionText = "Tell me about yourself.",
            whatWorked = "Clear structure.",
            couldImprove = "Add metrics.",
            tryNextTime = "Quantify impact.",
        ),
    ),
    professionalCommunication = listOf("Steady pace."),
    createdAt = "2026-09-28T00:00:00.000Z",
)

private class FakeFeedbackApi(
    var generateResponses: ArrayDeque<Response<FeedbackDto>> = ArrayDeque(),
    var getResult: Result<FeedbackDto> = Result.success(feedbackDto()),
    var failure: Throwable? = null,
) : InterviewApi {
    var generateCalls = 0

    override suspend fun createExperienceProfile(body: CreateExperienceProfileRequest): CreateExperienceProfileResponse =
        throw UnsupportedOperationException()

    override suspend fun createConversation(body: CreateConversationRequest): CreateConversationResponse =
        throw UnsupportedOperationException()

    override suspend fun sendTurn(conversationId: String, body: SendTurnRequest): SendTurnResponse =
        throw UnsupportedOperationException()

    override suspend fun getOpening(conversationId: String): OpeningResponse =
        throw UnsupportedOperationException()

    override suspend fun submitRetry(
        conversationId: String,
        body: SubmitRetryRequestDto,
    ): retrofit2.Response<RetryResponseDto> =
        throw UnsupportedOperationException()

    override suspend fun getCurrentRetry(
        conversationId: String,
        answerMessageId: String,
    ): RetryResponseDto = throw UnsupportedOperationException()

    override suspend fun regenerateRetryFeedback(
        conversationId: String,
        answerMessageId: String,
    ): RetryResponseDto = throw UnsupportedOperationException()


    override suspend fun getConversation(conversationId: String): GetConversationResponse =
        throw UnsupportedOperationException()

    override suspend fun generateFeedback(conversationId: String): Response<FeedbackDto> {
        failure?.let { throw it }
        generateCalls++
        return generateResponses.removeFirst()
    }

    override suspend fun getFeedback(conversationId: String): FeedbackDto {
        failure?.let { throw it }
        return getResult.getOrThrow()
    }

    override suspend fun parseResume(file: okhttp3.MultipartBody.Part): ResumeParseResponse =
        throw UnsupportedOperationException()

    override suspend fun sendVoiceTurn(
        conversationId: String,
        audio: okhttp3.MultipartBody.Part,
        clientTurnId: okhttp3.RequestBody,
    ): VoiceTurnResponse =
        throw UnsupportedOperationException()
}

class FeedbackRepositoryTest {

    @Test
    fun dto_parsesBackendShape() {
        val parsed = Json.decodeFromString<FeedbackDto>(
            """
            {
              "conversationId": "c1",
              "promptVersion": "v3",
              "overall": "Good.",
              "answerItems": [
                {"answerMessageId": "a1", "questionMessageId": "q1",
                 "whatWorked": "w", "couldImprove": "c", "tryNextTime": "t"}
              ],
              "professionalCommunication": ["p1"],
              "createdAt": "2026-01-01T00:00:00Z"
            }
            """.trimIndent(),
        )
        assertEquals("c1", parsed.conversationId)
        assertEquals("Good.", parsed.overall)
        assertEquals("a1", parsed.answerItems.single().answerMessageId)
        assertNull(parsed.answerItems.single().questionText)
        assertEquals(listOf("p1"), parsed.professionalCommunication)
    }

    @Test
    fun dto_parsesPracticeOpportunityTrueAndFalse() {
        val yes = Json.decodeFromString<FeedbackDto>(
            """{"conversationId":"c","overall":"o","answerItems":[{"answerMessageId":"a","practiceOpportunity":true}]}""",
        )
        val no = Json.decodeFromString<FeedbackDto>(
            """{"conversationId":"c","overall":"o","answerItems":[{"answerMessageId":"a","practiceOpportunity":false}]}""",
        )
        assertEquals(true, yes.answerItems.single().practiceOpportunity)
        assertEquals(false, no.answerItems.single().practiceOpportunity)
    }

    @Test
    fun dto_missingPracticeOpportunityDefaultsFalseNeverTrue() {
        val parsed = Json.decodeFromString<FeedbackDto>(
            """{"conversationId":"c","overall":"o","answerItems":[{"answerMessageId":"a"}]}""",
        )
        assertEquals(false, parsed.answerItems.single().practiceOpportunity)
    }

    @Test
    fun generate_201_isNewlyCreated() = runBlocking {
        val api = FakeFeedbackApi(generateResponses = ArrayDeque(listOf(Response.success(201, feedbackDto()))))
        val result = FeedbackRepository(api).generateFeedback("conv-1") as ApiResult.Success
        assertTrue(result.value.newlyCreated)
        assertEquals("Solid answers overall.", result.value.feedback.overall)
        assertEquals("Tell me about yourself.", result.value.feedback.answerItems.single().questionText)
    }

    @Test
    fun generate_200_isExisting() = runBlocking {
        val api = FakeFeedbackApi(generateResponses = ArrayDeque(listOf(Response.success(feedbackDto()))))
        val result = FeedbackRepository(api).generateFeedback("conv-1") as ApiResult.Success
        assertFalse(result.value.newlyCreated)
        assertEquals("conv-1", result.value.feedback.conversationId)
    }

    @Test
    fun generate_409_mapsHttpError() = runBlocking {
        val api = FakeFeedbackApi(
            generateResponses = ArrayDeque(
                listOf(Response.error(409, "closed".toResponseBody("text/plain".toMediaType()))),
            ),
        )
        val result = FeedbackRepository(api).generateFeedback("conv-1")
        assertTrue(result is ApiResult.Error && result.kind == ErrorKind.HTTP && result.httpCode == 409)
    }

    @Test
    fun get_200_mapsFeedback() = runBlocking {
        val result = FeedbackRepository(FakeFeedbackApi()).getFeedback("conv-1") as ApiResult.Success
        assertEquals(listOf("Steady pace."), result.value.professionalCommunication)
    }

    @Test
    fun get_networkError() = runBlocking {
        val result = FeedbackRepository(FakeFeedbackApi(failure = IOException("down"))).getFeedback("c")
        assertTrue(result is ApiResult.Error && result.kind == ErrorKind.NETWORK)
    }

    @Test
    fun generate_malformedError() = runBlocking {
        val result = FeedbackRepository(FakeFeedbackApi(failure = SerializationException("bad")))
            .generateFeedback("c")
        assertTrue(result is ApiResult.Error && result.kind == ErrorKind.MALFORMED)
    }
}
