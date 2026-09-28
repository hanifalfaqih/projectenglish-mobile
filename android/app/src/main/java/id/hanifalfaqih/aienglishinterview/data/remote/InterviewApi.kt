package id.hanifalfaqih.aienglishinterview.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.MultipartBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path

/**
 * Retrofit service for the frozen backend contract. DTOs are serialization
 * shapes only; mapping to domain models happens in the repositories.
 */

// --- Experience ---

@Serializable
data class ExperienceItemDto(
    val title: String,
    val organization: String? = null,
    val role: String? = null,
    val description: String,
    val skills: List<String> = emptyList(),
)

@Serializable
data class CreateExperienceProfileRequest(
    val items: List<ExperienceItemDto>,
)

@Serializable
data class CreateExperienceProfileResponse(
    val id: String,
)

@Serializable
data class ResumeParseResponse(
    val items: List<ExperienceItemDto> = emptyList(),
)

// --- Conversation ---

@Serializable
data class CreateConversationRequest(
    val experienceProfileId: String? = null,
)

@Serializable
data class TopicDto(
    val id: String,
    val label: String,
    val covered: Boolean,
)

@Serializable
data class ConversationStateDto(
    val phase: String,
    val topics: List<TopicDto> = emptyList(),
    val currentTopicId: String? = null,
    val questionCount: Int = 0,
)

@Serializable
data class CreateConversationResponse(
    val id: String,
    val status: String,
    val state: ConversationStateDto,
)

@Serializable
data class SendTurnRequest(
    val clientTurnId: String,
    val message: String,
)

@Serializable
data class SendTurnResponse(
    val assistantMessage: String,
    val state: ConversationStateDto,
    val status: String,
    val closing: Boolean = false,
)

interface InterviewApi {
    @POST("experience-profiles")
    suspend fun createExperienceProfile(
        @Body body: CreateExperienceProfileRequest,
    ): CreateExperienceProfileResponse

    /**
     * Resume parse endpoint. Response items intentionally match the
     * experience-profile item contract, so [ExperienceItemDto] is reused.
     */
    @Multipart
    @POST("resume/parse")
    suspend fun parseResume(
        @Part file: MultipartBody.Part,
    ): ResumeParseResponse

    @POST("conversations")
    suspend fun createConversation(
        @Body body: CreateConversationRequest,
    ): CreateConversationResponse

    @POST("conversations/{id}/turns")
    suspend fun sendTurn(
        @Path("id") conversationId: String,
        @Body body: SendTurnRequest,
    ): SendTurnResponse

    @GET("conversations/{id}")
    suspend fun getConversation(
        @Path("id") conversationId: String,
    ): GetConversationResponse

    /**
     * Generate-or-return feedback. Returns the raw [Response] so callers can
     * distinguish 201 (newly generated) from 200 (existing artifact).
     */
    @POST("conversations/{id}/feedback")
    suspend fun generateFeedback(
        @Path("id") conversationId: String,
    ): retrofit2.Response<FeedbackDto>

    @GET("conversations/{id}/feedback")
    suspend fun getFeedback(
        @Path("id") conversationId: String,
    ): FeedbackDto
}

// --- Feedback ---

@Serializable
data class AnswerFeedbackItemDto(
    val answerMessageId: String,
    val questionMessageId: String? = null,
    /** Derived at read time by the backend; absent on some responses. */
    val questionText: String? = null,
    val whatWorked: String? = null,
    val couldImprove: String? = null,
    val tryNextTime: String? = null,
)

@Serializable
data class FeedbackDto(
    val conversationId: String,
    val promptVersion: String = "",
    val overall: String,
    val answerItems: List<AnswerFeedbackItemDto> = emptyList(),
    val professionalCommunication: List<String>? = null,
    val createdAt: String = "",
)

@Serializable
data class TranscriptMessageDto(
    val id: String,
    val role: String,
    val content: String,
    @SerialName("createdAt")
    val createdAt: String = "",
)

@Serializable
data class GetConversationResponse(
    val id: String,
    val status: String,
    val state: ConversationStateDto,
    val experienceProfileId: String? = null,
    val transcript: List<TranscriptMessageDto> = emptyList(),
)
