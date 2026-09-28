package id.hanifalfaqih.aienglishinterview.data.model

/**
 * Domain models for the interview journey. Plain Kotlin — no serialization
 * annotations (those live on the DTOs in `data/remote`).
 */

/** A single professional/academic experience, as entered by the user. */
data class ExperienceItem(
    val title: String,
    val organization: String?,
    val role: String?,
    val description: String,
    val skills: List<String>,
)

/** Interview lifecycle state returned by the backend after each turn. */
data class ConversationState(
    val phase: String,
    val topics: List<Topic>,
    val currentTopicId: String?,
    val questionCount: Int,
)

data class Topic(
    val id: String,
    val label: String,
    val covered: Boolean,
)

/** A newly created conversation. */
data class Conversation(
    val id: String,
    val status: String,
) {
    val isClosed: Boolean get() = status == "closed"
}

/** Result of submitting one interview turn. */
data class TurnResult(
    val assistantMessage: String,
    val state: ConversationState,
    val status: String,
    val closing: Boolean,
) {
    val isClosed: Boolean get() = status == "closed" || closing
}

/** One visible line in the interview transcript. */
data class ChatLine(
    val isUser: Boolean,
    val text: String,
)

/** Answer-level feedback for one interview answer. */
data class AnswerFeedback(
    val answerMessageId: String,
    val questionMessageId: String?,
    val questionText: String?,
    val whatWorked: String?,
    val couldImprove: String?,
    val tryNextTime: String?,
)

/** Professional communication feedback for a completed interview. */
data class Feedback(
    val conversationId: String,
    val promptVersion: String,
    val overall: String,
    val answerItems: List<AnswerFeedback>,
    val professionalCommunication: List<String>,
    val createdAt: String,
)
