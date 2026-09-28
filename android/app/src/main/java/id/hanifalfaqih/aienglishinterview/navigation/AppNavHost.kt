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
import id.hanifalfaqih.aienglishinterview.feature.experience.ExperienceScreen
import id.hanifalfaqih.aienglishinterview.feature.experience.ReviewScreen
import id.hanifalfaqih.aienglishinterview.feature.feedback.FeedbackScreen
import id.hanifalfaqih.aienglishinterview.feature.interview.InterviewScreen
import id.hanifalfaqih.aienglishinterview.feature.premium.PaywallScreen

/**
 * Single NavHost for the minimum journey:
 * Experience -> Interview -> Feedback.
 */
@Composable
fun AppNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = Routes.EXPERIENCE,
        modifier = modifier,
    ) {
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
            val recognizer = remember(conversationId) { VoiceProviderFactory.recognizer(context) }
            val synthesizer = remember(conversationId) { VoiceProviderFactory.synthesizer(context) }
            InterviewScreen(
                conversationId = conversationId,
                onCompleteInterview = {
                    navController.navigate(Routes.feedback(conversationId))
                },
                onBack = { navController.popBackStack() },
                recognizer = recognizer,
                synthesizer = synthesizer,
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
            FeedbackScreen(
                conversationId = conversationId,
                onBack = { navController.popBackStack() },
                onGoPremium = {
                    navController.navigate(Routes.PREMIUM)
                },
            )
        }
    }
}
