package id.hanifalfaqih.aienglishinterview.data.repository

import id.hanifalfaqih.aienglishinterview.core.network.ApiResult
import id.hanifalfaqih.aienglishinterview.core.network.ErrorKind
import id.hanifalfaqih.aienglishinterview.data.model.AnswerFeedback
import id.hanifalfaqih.aienglishinterview.data.model.Feedback
import id.hanifalfaqih.aienglishinterview.data.remote.AnswerFeedbackItemDto
import id.hanifalfaqih.aienglishinterview.data.remote.ApiProvider
import id.hanifalfaqih.aienglishinterview.data.remote.FeedbackDto
import id.hanifalfaqih.aienglishinterview.data.remote.InterviewApi

/**
 * Result of a feedback generation request. The backend returns 201 for a
 * newly generated artifact and 200 for an already-existing one; both carry
 * the same body shape and both are success here.
 */
data class FeedbackGeneration(
    val feedback: Feedback,
    val newlyCreated: Boolean,
)

/**
 * Feedback for completed interviews. Generation is idempotent server-side
 * (unique per conversation), so repeated calls are safe.
 */
class FeedbackRepository(
    private val api: InterviewApi = ApiProvider.api,
) {
    suspend fun generateFeedback(conversationId: String): ApiResult<FeedbackGeneration> {
        return when (val result = apiCall { api.generateFeedback(conversationId) }) {
            is ApiResult.Success -> {
                val response = result.value
                if (!response.isSuccessful) {
                    return ApiResult.Error(
                        kind = ErrorKind.HTTP,
                        message = "Server error ${response.code()}",
                        httpCode = response.code(),
                    )
                }
                val body = response.body()
                    ?: return ApiResult.Error(
                        kind = ErrorKind.MALFORMED,
                        message = "Unexpected server response.",
                    )
                ApiResult.Success(
                    FeedbackGeneration(
                        feedback = body.toDomain(),
                        newlyCreated = response.code() == 201,
                    ),
                )
            }
            is ApiResult.Error -> result
        }
    }

    suspend fun getFeedback(conversationId: String): ApiResult<Feedback> {
        return when (val result = apiCall { api.getFeedback(conversationId) }) {
            is ApiResult.Success -> ApiResult.Success(result.value.toDomain())
            is ApiResult.Error -> result
        }
    }

    private fun FeedbackDto.toDomain() = Feedback(
        conversationId = conversationId,
        promptVersion = promptVersion,
        overall = overall,
        answerItems = answerItems.map { it.toDomain() },
        professionalCommunication = professionalCommunication.orEmpty(),
        createdAt = createdAt,
    )

    private fun AnswerFeedbackItemDto.toDomain() = AnswerFeedback(
        answerMessageId = answerMessageId,
        questionMessageId = questionMessageId,
        questionText = questionText,
        whatWorked = whatWorked,
        couldImprove = couldImprove,
        tryNextTime = tryNextTime,
    )
}
