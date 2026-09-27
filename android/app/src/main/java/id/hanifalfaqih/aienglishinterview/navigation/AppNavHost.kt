package id.hanifalfaqih.aienglishinterview.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import id.hanifalfaqih.aienglishinterview.feature.experience.ExperienceScreen
import id.hanifalfaqih.aienglishinterview.feature.feedback.FeedbackScreen
import id.hanifalfaqih.aienglishinterview.feature.interview.InterviewScreen

/**
 * Single NavHost for the minimum journey:
 * Experience -> Interview -> Feedback.
 *
 * UI skeleton only: no backend, voice, or monetization calls originate here.
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
                onStartInterview = {
                    navController.navigate(
                        Routes.interview(Routes.DEMO_CONVERSATION_ID),
                    )
                },
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
            InterviewScreen(
                conversationId = conversationId,
                onCompleteInterview = {
                    navController.navigate(Routes.feedback(conversationId))
                },
                onBack = { navController.popBackStack() },
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
            )
        }
    }
}
