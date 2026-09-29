package id.hanifalfaqih.aienglishinterview.data.repository

import id.hanifalfaqih.aienglishinterview.core.network.ApiResult
import id.hanifalfaqih.aienglishinterview.core.network.ErrorKind
import id.hanifalfaqih.aienglishinterview.data.model.Retry
import id.hanifalfaqih.aienglishinterview.data.model.RetryFeedback
import id.hanifalfaqih.aienglishinterview.data.model.RetrySubmission
import id.hanifalfaqih.aienglishinterview.data.remote.ApiProvider
import id.hanifalfaqih.aienglishinterview.data.remote.InterviewApi
import id.hanifalfaqih.aienglishinterview.data.remote.RetryFeedbackDto
import id.hanifalfaqih.aienglishinterview.data.remote.RetryResponseDto
import id.hanifalfaqih.aienglishinterview.data.remote.SubmitRetryRequestDto

/**
 * M12 targeted retry: re-answer one specific original answer. Transport for
 * the backend contract only — no eligibility heuristics, no premium logic,
 * no interview state. Semantically separate from M15 fresh Practice Again
 * (new conversation), which lives in [FeedbackViewModel]+[ConversationRepository].
 */
class RetryRepository(
    private val api: InterviewApi = ApiProvider.api,
) {
    suspend fun submitRetry(
        conversationId: String,
        answerMessageId: String,
        questionMessageId: String,
        retryAnswer: String,
        retryClientKey: String,
    ): ApiResult<RetrySubmission> {
        return when (
            val result = apiCall {
                api.submitRetry(
                    conversationId,
                    SubmitRetryRequestDto(
                        answerMessageId = answerMessageId,
                        questionMessageId = questionMessageId,
                        retryAnswer = retryAnswer,
                        retryClientKey = retryClientKey,
                    ),
                )
            }
        ) {
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
                    RetrySubmission(
                        retry = body.toDomain(),
                        newlyCreated = response.code() == 201,
                    ),
                )
            }
            is ApiResult.Error -> result
        }
    }

    suspend fun getCurrentRetry(
        conversationId: String,
        answerMessageId: String,
    ): ApiResult<Retry> {
        return when (
            val result = apiCall { api.getCurrentRetry(conversationId, answerMessageId) }
        ) {
            is ApiResult.Success -> ApiResult.Success(result.value.toDomain())
            is ApiResult.Error -> result
        }
    }

    suspend fun regenerateRetryFeedback(
        conversationId: String,
        answerMessageId: String,
    ): ApiResult<Retry> {
        return when (
            val result = apiCall { api.regenerateRetryFeedback(conversationId, answerMessageId) }
        ) {
            is ApiResult.Success -> ApiResult.Success(result.value.toDomain())
            is ApiResult.Error -> result
        }
    }

    private fun RetryResponseDto.toDomain() = Retry(
        id = id,
        conversationId = conversationId,
        answerMessageId = answerMessageId,
        questionMessageId = questionMessageId,
        retryClientKey = retryClientKey,
        retryAnswer = retryAnswer,
        feedback = feedback?.toDomain(),
        feedbackStatus = feedbackStatus,
        feedbackPromptVersion = feedbackPromptVersion,
        originalQuestion = originalQuestion,
        originalAnswer = originalAnswer,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun RetryFeedbackDto.toDomain() = RetryFeedback(
        overall = overall,
        whatWorked = whatWorked,
        couldImprove = couldImprove,
        tryNextTime = tryNextTime,
        professionalCommunication = professionalCommunication,
    )
}
