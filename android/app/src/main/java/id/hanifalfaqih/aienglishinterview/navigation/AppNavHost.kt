package id.hanifalfaqih.aienglishinterview.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import id.hanifalfaqih.aienglishinterview.core.voice.VoiceProviderFactory
import id.hanifalfaqih.aienglishinterview.feature.completion.InterviewCompleteScreen
import id.hanifalfaqih.aienglishinterview.feature.experience.ExperienceConfirmationScreen
import id.hanifalfaqih.aienglishinterview.feature.experience.ExperienceScreen
import id.hanifalfaqih.aienglishinterview.feature.experience.ExperienceTypeScreen
import id.hanifalfaqih.aienglishinterview.feature.experience.ImportResumeScreen
import id.hanifalfaqih.aienglishinterview.feature.experience.ManualDraft
import id.hanifalfaqih.aienglishinterview.feature.experience.ManualFormScreen
import id.hanifalfaqih.aienglishinterview.feature.experience.MyExperienceScreen
import id.hanifalfaqih.aienglishinterview.feature.experience.SelectExperienceScreen
import id.hanifalfaqih.aienglishinterview.feature.experience.SelectedExperience
import id.hanifalfaqih.aienglishinterview.feature.experience.ReviewScreen
import id.hanifalfaqih.aienglishinterview.feature.feedback.FeedbackScreen
import id.hanifalfaqih.aienglishinterview.feature.interview.InterviewScreen
import id.hanifalfaqih.aienglishinterview.feature.premium.PaywallScreen
import id.hanifalfaqih.aienglishinterview.feature.welcome.WelcomeScreen

/**
 * Single NavHost for the minimum journey:
 * Welcome -> My Experience -> type/form/import/review/confirmation
 * -> Interview -> Feedback.
 */
@Composable
fun AppNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    /** Null means the default start destination (all production flows). */
    startDestination: String? = null,
) {
    NavHost(
        navController = navController,
        startDestination = startDestination ?: Routes.WELCOME,
        modifier = modifier,
    ) {
        composable(Routes.WELCOME) {
            WelcomeScreen(
                onGetStarted = {
                    navController.navigate(Routes.MY_EXPERIENCE)
                },
            )
        }
        composable(Routes.MY_EXPERIENCE) {
            MyExperienceScreen(
                onImportResume = {
                    navController.navigate(Routes.IMPORT_RESUME)
                },
                onAddManually = {
                    navController.navigate(Routes.EXPERIENCE_TYPE)
                },
            )
        }
        composable(Routes.EXPERIENCE_TYPE) {
            ExperienceTypeScreen(
                onContinue = { type ->
                    ManualDraft.type = type
                    navController.navigate(Routes.MANUAL_FORM)
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.MANUAL_FORM) {
            ManualFormScreen(
                onContinue = {
                    SelectedExperience.item = ManualDraft.toItem()
                    SelectedExperience.typeLabel = ManualDraft.type?.label
                    navController.navigate(Routes.EXPERIENCE_CONFIRMATION)
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.IMPORT_RESUME) {
            ImportResumeScreen(
                onParsed = {
                    navController.navigate(Routes.REVIEW_EXPERIENCES)
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.REVIEW_EXPERIENCES) {
            SelectExperienceScreen(
                onSelected = {
                    navController.navigate(Routes.EXPERIENCE_CONFIRMATION)
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.EXPERIENCE_CONFIRMATION) {
            ExperienceConfirmationScreen(
                onInterviewReady = { conversationId ->
                    navController.navigate(Routes.interview(conversationId))
                },
                onEdit = { navController.popBackStack() },
            )
        }
        composable(Routes.EXPERIENCE) {
            ExperienceScreen(
                onInterviewReady = { conversationId ->
                    navController.navigate(Routes.interview(conversationId))
                },
                onReviewReady = {
                    navController.navigate(Routes.REVIEW)
                },
                onGoPremium = {
                    navController.navigate(Routes.PREMIUM)
                },
            )
        }
        composable(Routes.REVIEW) {
            ReviewScreen(
                onInterviewReady = { conversationId ->
                    navController.navigate(Routes.interview(conversationId))
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.PREMIUM) {
            PaywallScreen(
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = Routes.INTERVIEW,
            arguments = listOf(
                navArgument(Routes.ARG_CONVERSATION_ID) {
                    type = NavType.StringType
                },
            ),
        ) { backStackEntry ->
            val conversationId =
                backStackEntry.arguments?.getString(Routes.ARG_CONVERSATION_ID).orEmpty()
            // Production voice engines, created once per navigation entry.
            // Previews render InterviewScreen without engines (pending state).
            val context = LocalContext.current
            val recognizer = remember(conversationId) { VoiceProviderFactory.recognizer() }
            val synthesizer = remember(conversationId) { VoiceProviderFactory.synthesizer(context) }
            val audioPlayer = remember(conversationId) { VoiceProviderFactory.audioPlayer() }
            InterviewScreen(
                conversationId = conversationId,
                onCompleteInterview = {
                    navController.navigate(Routes.completion(conversationId))
                },
                onBack = { navController.popBackStack() },
                recognizer = recognizer,
                synthesizer = synthesizer,
                audioPlayer = audioPlayer,
            )
        }
        composable(
            route = Routes.COMPLETION,
            arguments = listOf(
                navArgument(Routes.ARG_CONVERSATION_ID) {
                    type = NavType.StringType
                },
            ),
        ) { backStackEntry ->
            val conversationId =
                backStackEntry.arguments?.getString(Routes.ARG_CONVERSATION_ID).orEmpty()
            InterviewCompleteScreen(
                onViewFeedback = {
                    navController.navigate(Routes.feedback(conversationId))
                },
                // A closed interview must never be resumed: pop back to the
                // existing entry screen instead of pushing another copy.
                onBack = {
                    navController.popBackStack(Routes.MY_EXPERIENCE, inclusive = false)
                },
            )
        }
        composable(
            route = Routes.FEEDBACK,
            arguments = listOf(
                navArgument(Routes.ARG_CONVERSATION_ID) {
                    type = NavType.StringType
                },
            ),
        ) { backStackEntry ->
            val conversationId =
                backStackEntry.arguments?.getString(Routes.ARG_CONVERSATION_ID).orEmpty()
            val recognizer = remember(conversationId) { VoiceProviderFactory.recognizer() }
            FeedbackScreen(
                conversationId = conversationId,
                onBack = { navController.popBackStack() },
                onGoPremium = {
                    navController.navigate(Routes.PREMIUM)
                },
                // New session on the same experience; the closed
                // conversation stays behind and can never be resumed.
                onPracticeAgain = { newConversationId ->
                    navController.navigate(Routes.interview(newConversationId)) {
                        popUpTo(Routes.MY_EXPERIENCE) { inclusive = false }
                    }
                },
                recognizer = recognizer,
            )
        }
    }
}
