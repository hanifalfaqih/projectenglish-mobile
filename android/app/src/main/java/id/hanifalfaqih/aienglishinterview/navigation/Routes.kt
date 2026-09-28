package id.hanifalfaqih.aienglishinterview.navigation

/**
 * Route definitions for the minimum Shipaton journey:
 * My Experience -> Interview -> Feedback.
 *
 * Interview and feedback routes carry a [conversationId] argument because the
 * backend contract is conversation-based. The id always comes from a real
 * `POST /conversations` response.
 */
object Routes {
    const val EXPERIENCE = "experience"

    const val REVIEW = "review"

    const val PREMIUM = "premium"

    const val ARG_CONVERSATION_ID = "conversationId"

    const val INTERVIEW = "interview/{$ARG_CONVERSATION_ID}"
    const val FEEDBACK = "feedback/{$ARG_CONVERSATION_ID}"

    fun interview(conversationId: String): String = "interview/$conversationId"

    fun feedback(conversationId: String): String = "feedback/$conversationId"
}
