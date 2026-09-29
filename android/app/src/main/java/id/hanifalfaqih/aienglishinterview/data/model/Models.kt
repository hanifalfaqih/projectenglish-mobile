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

/** Conversation lookup: status plus the experience profile for retry. */
data class ConversationDetail(
    val id: String,
    val status: String,
    val experienceProfileId: String?,
) {
    val isClosed: Boolean get() = status == "closed"
}

/** One visible line in the interview transcript. */
data class ChatLine(
    val isUser: Boolean,
    val text: String,
)

/** Result of submitting one interview turn. */
data class TurnResult(
    val assistantMessage: String,
    val state: ConversationState,
    val status: String,
    val closing: Boolean,
) {
    val isClosed: Boolean get() = status == "closed" || closing
}

/** Result of submitting one voice turn: transcript plus turn outcome. */
data class VoiceTurn(
    val transcript: String,
    val assistantMessage: String,
    val state: ConversationState,
    val status: String,
    val closing: Boolean,
    /** Raw PCM bytes when backend synthesis succeeded; null otherwise. */
    val audio: ByteArray?,
    /** Safe client-readable reason when audio is null despite a good turn. */
    val audioError: String?,
) {
    val isClosed: Boolean get() = status == "closed" || closing
}

/** Result of the AI-first opening: first message plus optional audio. */
data class Opening(
    val assistantMessage: String,
    val audio: ByteArray?,
    val audioError: String?,
)

/** Answer-level feedback for one interview answer. */
data class AnswerFeedback(
    val answerMessageId: String,
    val questionMessageId: String?,
    val questionText: String?,
    val whatWorked: String?,
    val couldImprove: String?,
    val tryNextTime: String?,
    /** Server-owned retry eligibility; absent legacy payloads read as false. */
    val practiceOpportunity: Boolean = false,
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

/**
 * Qualitative feedback on one retry answer. Deliberately separate from
 * M11 [AnswerFeedback]: the original feedback stays immutable while the
 * retry carries its own artifact.
 */
data class RetryFeedback(
    val overall: String,
    val whatWorked: String?,
    val couldImprove: String?,
    val tryNextTime: String?,
    val professionalCommunication: List<String>?,
)

/** One targeted retry artifact: the new answer plus its own feedback. */
data class Retry(
    val id: String,
    val conversationId: String,
    val answerMessageId: String,
    val questionMessageId: String?,
    val retryClientKey: String,
    val retryAnswer: String,
    val feedback: RetryFeedback?,
    val feedbackStatus: String,
    val feedbackPromptVersion: String?,
    val originalQuestion: String,
    val originalAnswer: String,
    val createdAt: String,
    val updatedAt: String,
)

/** Result of a retry submission: 201 created vs 200 idempotent replay. */
data class RetrySubmission(
    val retry: Retry,
    val newlyCreated: Boolean,
)
