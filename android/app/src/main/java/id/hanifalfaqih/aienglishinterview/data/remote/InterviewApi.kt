package id.hanifalfaqih.aienglishinterview.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.MultipartBody
import okhttp3.RequestBody
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

@Serializable
data class VoiceAudioDto(
    val format: String = "",
    val sampleRateHz: Int = 0,
    val channels: Int = 0,
    val data: String = "",
)

@Serializable
data class VoiceTurnResponse(
    val transcript: String,
    val assistantMessage: String,
    val state: ConversationStateDto,
    val status: String,
    val closing: Boolean = false,
    val audio: VoiceAudioDto? = null,
    val audioError: String? = null,
)

/**
 * AI-first opening response. Same audio envelope as voice turns: PCM audio
 * when backend synthesis succeeded, null + audioError otherwise.
 */
@Serializable
data class OpeningResponse(
    val assistantMessage: String,
    val audio: VoiceAudioDto? = null,
    val audioError: String? = null,
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

    /**
     * AI-first opening for a fresh conversation: returns the interviewer's
     * first message plus optional PCM audio. 409 when turns already exist.
     */
    @POST("conversations/{id}/opening")
    suspend fun getOpening(
        @Path("id") conversationId: String,
    ): OpeningResponse

    /**
     * Voice turn: raw 16-bit mono 16 kHz PCM as multipart `audio` plus the
     * `clientTurnId` text field. The backend transcribes with Qwen ASR and
     * feeds the transcript through the same turn pipeline as [sendTurn].
     */
    @Multipart
    @POST("conversations/{id}/voice-turn")
    suspend fun sendVoiceTurn(
        @Path("id") conversationId: String,
        @Part audio: MultipartBody.Part,
        @Part("clientTurnId") clientTurnId: RequestBody,
    ): VoiceTurnResponse

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

    /**
     * M12 targeted retry: re-answer one specific original answer.
     * Returns the raw response so callers distinguish 201 (created) from
     * 200 (idempotent replay of the same retryClientKey).
     */
    @POST("conversations/{id}/retries")
    suspend fun submitRetry(
        @Path("id") conversationId: String,
        @Body body: SubmitRetryRequestDto,
    ): retrofit2.Response<RetryResponseDto>

    /** Current retry artifact for one answer. 404 when none exists. */
    @GET("conversations/{id}/retries/{answerId}")
    suspend fun getCurrentRetry(
        @Path("id") conversationId: String,
        @Path("answerId") answerMessageId: String,
    ): RetryResponseDto

    /** Regenerate feedback for an existing failed retry. */
    @POST("conversations/{id}/retries/{answerId}/feedback")
    suspend fun regenerateRetryFeedback(
        @Path("id") conversationId: String,
        @Path("answerId") answerMessageId: String,
    ): RetryResponseDto
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
    /**
     * Server-owned practice-eligibility marker (M11). Absent on legacy
     * payloads, which must read as false — never inferred client-side.
     */
    val practiceOpportunity: Boolean = false,
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

// --- M12 targeted retry ---

/**
 * Retry submission. Field names match the backend contract exactly;
 * retryClientKey is a per-request idempotency key (a fresh UUID per
 * submission), never a turn/clientTurnId.
 */
@Serializable
data class SubmitRetryRequestDto(
    val answerMessageId: String,
    val questionMessageId: String,
    val retryAnswer: String,
    val retryClientKey: String,
)

/** Qualitative retry feedback. Separate from M11 feedback by design. */
@Serializable
data class RetryFeedbackDto(
    val overall: String,
    val whatWorked: String? = null,
    val couldImprove: String? = null,
    val tryNextTime: String? = null,
    val professionalCommunication: List<String>? = null,
)

/** Retry artifact response: 201 created, 200 idempotent replay. */
@Serializable
data class RetryResponseDto(
    val id: String,
    val conversationId: String,
    val answerMessageId: String,
    val questionMessageId: String? = null,
    val retryClientKey: String,
    val retryAnswer: String,
    val feedback: RetryFeedbackDto? = null,
    val feedbackStatus: String = "",
    val feedbackPromptVersion: String? = null,
    val originalQuestion: String = "",
    val originalAnswer: String = "",
    val createdAt: String = "",
    val updatedAt: String = "",
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
