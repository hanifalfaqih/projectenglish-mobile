package id.hanifalfaqih.aienglishinterview.data.repository

import id.hanifalfaqih.aienglishinterview.core.network.ApiResult
import id.hanifalfaqih.aienglishinterview.core.network.ErrorKind
import id.hanifalfaqih.aienglishinterview.data.model.ExperienceItem
import id.hanifalfaqih.aienglishinterview.data.remote.CreateConversationRequest
import id.hanifalfaqih.aienglishinterview.data.remote.CreateConversationResponse
import id.hanifalfaqih.aienglishinterview.data.remote.CreateExperienceProfileRequest
import id.hanifalfaqih.aienglishinterview.data.remote.CreateExperienceProfileResponse
import id.hanifalfaqih.aienglishinterview.data.remote.FeedbackDto
import id.hanifalfaqih.aienglishinterview.data.remote.ConversationStateDto
import id.hanifalfaqih.aienglishinterview.data.remote.GetConversationResponse
import id.hanifalfaqih.aienglishinterview.data.remote.InterviewApi
import id.hanifalfaqih.aienglishinterview.data.remote.ResumeParseResponse
import id.hanifalfaqih.aienglishinterview.data.remote.SendTurnRequest
import id.hanifalfaqih.aienglishinterview.data.remote.SendTurnResponse
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

private fun httpError(code: Int): HttpException =
    HttpException(Response.error<Any>(code, "e".toResponseBody("text/plain".toMediaType())))

private class FakeApi(
    var profileResult: Result<String> = Result.success("profile-1"),
    var conversationResult: Result<CreateConversationResponse> =
        Result.success(
            CreateConversationResponse(
                id = "conv-1",
                status = "active",
                state = ConversationStateDto("intro", emptyList(), null, 0),
            ),
        ),
    var turnResult: Result<SendTurnResponse> =
        Result.success(
            SendTurnResponse("Hello?", ConversationStateDto("intro", emptyList(), null, 1), "active", false),
        ),
    var failure: Throwable? = null,
) : InterviewApi {
    var lastProfileRequest: CreateExperienceProfileRequest? = null
    var lastConversationRequest: CreateConversationRequest? = null
    var lastTurn: Triple<String, String, String>? = null

    override suspend fun createExperienceProfile(body: CreateExperienceProfileRequest): CreateExperienceProfileResponse {
        failure?.let { throw it }
        lastProfileRequest = body
        return CreateExperienceProfileResponse(profileResult.getOrThrow())
    }

    override suspend fun createConversation(body: CreateConversationRequest): CreateConversationResponse {
        failure?.let { throw it }
        lastConversationRequest = body
        return conversationResult.getOrThrow()
    }

    override suspend fun sendTurn(conversationId: String, body: SendTurnRequest): SendTurnResponse {
        failure?.let { throw it }
        lastTurn = Triple(conversationId, body.clientTurnId, body.message)
        return turnResult.getOrThrow()
    }

    override suspend fun getConversation(conversationId: String): GetConversationResponse =
        throw UnsupportedOperationException()

    override suspend fun generateFeedback(conversationId: String): retrofit2.Response<FeedbackDto> =
        throw UnsupportedOperationException()

    override suspend fun getFeedback(conversationId: String): FeedbackDto =
        throw UnsupportedOperationException()

    override suspend fun parseResume(file: okhttp3.MultipartBody.Part): ResumeParseResponse =
        throw UnsupportedOperationException()
}

class RepositoryTest {

    private val item = ExperienceItem("T", "O", "R", "D", listOf("Kotlin"))

    @Test
    fun createProfile_mapsItemsAndReturnsId() = runBlocking {
        val api = FakeApi()
        val result = ExperienceRepository(api).createExperienceProfile(listOf(item))
        assertEquals(ApiResult.Success("profile-1"), result)
        val sent = api.lastProfileRequest!!.items.single()
        assertEquals("T", sent.title)
        assertEquals(listOf("Kotlin"), sent.skills)
    }

    @Test
    fun createProfile_httpError() = runBlocking {
        val result = ExperienceRepository(FakeApi(failure = httpError(400)))
            .createExperienceProfile(listOf(item))
        assertTrue(result is ApiResult.Error && result.kind == ErrorKind.HTTP && result.httpCode == 400)
    }

    @Test
    fun createProfile_networkError() = runBlocking {
        val result = ExperienceRepository(FakeApi(failure = IOException("down")))
            .createExperienceProfile(listOf(item))
        assertTrue(result is ApiResult.Error && result.kind == ErrorKind.NETWORK)
    }

    @Test
    fun createProfile_malformedError() = runBlocking {
        val result = ExperienceRepository(FakeApi(failure = SerializationException("bad")))
            .createExperienceProfile(listOf(item))
        assertTrue(result is ApiResult.Error && result.kind == ErrorKind.MALFORMED)
    }

    @Test
    fun createConversation_sendsProfileId() = runBlocking {
        val api = FakeApi()
        val result = ConversationRepository(api).createConversation("profile-1")
        assertEquals("conv-1", (result as ApiResult.Success).value.id)
        assertEquals("profile-1", api.lastConversationRequest!!.experienceProfileId)
    }

    @Test
    fun sendTurn_mapsStateAndClosing() = runBlocking {
        val api = FakeApi()
        val result = ConversationRepository(api).sendTurn("conv-1", "turn-1", "Hi") as ApiResult.Success
        assertEquals("Hello?", result.value.assistantMessage)
        assertEquals(1, result.value.state.questionCount)
        assertEquals("turn-1", api.lastTurn!!.second)
        assertEquals("Hi", api.lastTurn!!.third)
    }

    @Test
    fun sendTurn_closedDetected() = runBlocking {
        val api = FakeApi(
            turnResult = Result.success(
                SendTurnResponse("Bye", ConversationStateDto("wrap_up", emptyList(), null, 8), "closed", true),
            ),
        )
        val result = ConversationRepository(api).sendTurn("c", "t", "m") as ApiResult.Success
        assertTrue(result.value.isClosed)
    }

    @Test
    fun sendTurn_http409Surfaced() = runBlocking {
        val result = ConversationRepository(FakeApi(failure = httpError(409)))
            .sendTurn("c", "t", "m")
        assertTrue(result is ApiResult.Error && result.httpCode == 409)
    }
}
