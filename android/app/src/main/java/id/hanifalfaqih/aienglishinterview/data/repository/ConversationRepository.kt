package id.hanifalfaqih.aienglishinterview.data.repository

import id.hanifalfaqih.aienglishinterview.core.network.ApiResult
import id.hanifalfaqih.aienglishinterview.data.model.Conversation
import id.hanifalfaqih.aienglishinterview.data.model.ConversationState
import id.hanifalfaqih.aienglishinterview.data.model.Topic
import id.hanifalfaqih.aienglishinterview.data.model.TurnResult
import id.hanifalfaqih.aienglishinterview.data.remote.ApiProvider
import id.hanifalfaqih.aienglishinterview.data.remote.ConversationStateDto
import id.hanifalfaqih.aienglishinterview.data.remote.CreateConversationRequest
import id.hanifalfaqih.aienglishinterview.data.remote.InterviewApi
import id.hanifalfaqih.aienglishinterview.data.remote.SendTurnRequest

/**
 * Conversations: creation and turn submission. The caller owns
 * `clientTurnId` — generate a fresh UUID per new answer and reuse the same
 * id when retrying an ambiguous failure (the backend replays idempotently).
 */
class ConversationRepository(
    private val api: InterviewApi = ApiProvider.api,
) {
    suspend fun createConversation(experienceProfileId: String?): ApiResult<Conversation> {
        return when (
            val result = apiCall { api.createConversation(CreateConversationRequest(experienceProfileId)) }
        ) {
            is ApiResult.Success -> ApiResult.Success(
                Conversation(id = result.value.id, status = result.value.status),
            )
            is ApiResult.Error -> result
        }
    }

    suspend fun sendTurn(
        conversationId: String,
        clientTurnId: String,
        message: String,
    ): ApiResult<TurnResult> {
        return when (
            val result = apiCall { api.sendTurn(conversationId, SendTurnRequest(clientTurnId, message)) }
        ) {
            is ApiResult.Success -> ApiResult.Success(
                TurnResult(
                    assistantMessage = result.value.assistantMessage,
                    state = result.value.state.toDomain(),
                    status = result.value.status,
                    closing = result.value.closing,
                ),
            )
            is ApiResult.Error -> result
        }
    }

    private fun ConversationStateDto.toDomain() = ConversationState(
        phase = phase,
        topics = topics.map { Topic(id = it.id, label = it.label, covered = it.covered) },
        currentTopicId = currentTopicId,
        questionCount = questionCount,
    )
}
