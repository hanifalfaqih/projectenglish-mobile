package id.hanifalfaqih.aienglishinterview.navigation

/**
 * Route definitions for the minimum Shipaton journey:
 * My Experience -> Interview -> Feedback.
 *
 * Interview and feedback routes carry a [conversationId] argument because the
 * eventual backend contract is conversation-based. No backend is connected;
 * screens currently navigate with a placeholder id (see [Routes.interview]).
 */
object Routes {
    const val EXPERIENCE = "experience"

    const val ARG_CONVERSATION_ID = "conversationId"

    const val INTERVIEW = "interview/{$ARG_CONVERSATION_ID}"
    const val FEEDBACK = "feedback/{$ARG_CONVERSATION_ID}"

    /** Placeholder conversation id used until real conversation creation exists. */
    const val DEMO_CONVERSATION_ID = "demo-conversation"

    fun interview(conversationId: String): String = "interview/$conversationId"

    fun feedback(conversationId: String): String = "feedback/$conversationId"
}
