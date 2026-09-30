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
    const val WELCOME = "welcome"

    const val MY_EXPERIENCE = "my_experience"

    const val EXPERIENCE_TYPE = "experience_type"

    const val MANUAL_FORM = "manual_form"

    const val IMPORT_RESUME = "import_resume"

    const val REVIEW_EXPERIENCES = "review_experiences"

    const val EXPERIENCE_CONFIRMATION = "experience_confirmation"

    const val EXPERIENCE = "experience"

    const val REVIEW = "review"

    const val PREMIUM = "premium"

    const val ARG_CONVERSATION_ID = "conversationId"

    const val INTERVIEW = "interview/{$ARG_CONVERSATION_ID}"
    const val FEEDBACK = "feedback/{$ARG_CONVERSATION_ID}"
    const val COMPLETION = "completion/{$ARG_CONVERSATION_ID}"

    fun interview(conversationId: String): String = "interview/$conversationId"

    fun feedback(conversationId: String): String = "feedback/$conversationId"

    fun completion(conversationId: String): String = "completion/$conversationId"
}
