package id.hanifalfaqih.aienglishinterview.feature.feedback

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import id.hanifalfaqih.aienglishinterview.core.monetization.MonetizationProvider
import id.hanifalfaqih.aienglishinterview.core.monetization.MonetizationRepository
import id.hanifalfaqih.aienglishinterview.core.monetization.PremiumState
import id.hanifalfaqih.aienglishinterview.data.model.AnswerFeedback
import id.hanifalfaqih.aienglishinterview.data.model.Feedback
import id.hanifalfaqih.aienglishinterview.ui.theme.AIEnglishInterviewTheme

private class FeedbackViewModelFactory(
    private val conversationId: String,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return FeedbackViewModel(conversationId) as T
    }
}

/**
 * Professional communication feedback for the completed interview, rendered
 * from the real backend artifact: overall, per-answer items, and
 * professional-communication observations.
 */
@Composable
fun FeedbackScreen(
    conversationId: String,
    onBack: () -> Unit,
    onGoPremium: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FeedbackViewModel = viewModel(
        factory = FeedbackViewModelFactory(conversationId),
    ),
    monetization: MonetizationRepository = MonetizationProvider.repository,
) {
    val premiumState by monetization.premiumState.collectAsState()
    LaunchedEffect(conversationId) {
        monetization.refresh()
    }
    val isPremium = (premiumState as? PremiumState.Determined)?.isPremium == true
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = "Interview Feedback", style = MaterialTheme.typography.headlineMedium)

        when (val state = viewModel.uiState) {
            is FeedbackUiState.Loading -> {
                CircularProgressIndicator()
                Text(
                    text = "Generating your feedback…",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            is FeedbackUiState.Error -> {
                Text(
                    text = state.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = { viewModel.retry() }) {
                    Text("Retry")
                }
                OutlinedButton(onClick = onBack) {
                    Text("Back")
                }
            }
            is FeedbackUiState.Content -> {
                FeedbackContent(
                    feedback = state.feedback,
                    isPremium = isPremium,
                    onGoPremium = onGoPremium,
                    onBack = onBack,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun FeedbackContent(
    feedback: Feedback,
    isPremium: Boolean,
    onGoPremium: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Overall",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = feedback.overall,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }

        if (isPremium) {
            if (feedback.answerItems.isNotEmpty()) {
                item {
                    Text(
                        text = "Answer feedback",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                items(feedback.answerItems) { item ->
                    AnswerFeedbackCard(item = item)
                }
            }

            if (feedback.professionalCommunication.isNotEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "Professional communication",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            feedback.professionalCommunication.forEach { point ->
                                Text(
                                    text = "• $point",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
        } else {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Premium detail",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = "Per-answer breakdown and professional " +
                                "communication coaching are premium.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        OutlinedButton(
                            onClick = onGoPremium,
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            Text("Unlock with Premium")
                        }
                    }
                }
            }
        }

        item {
            Text(
                text = "Generated ${feedback.createdAt}",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text("Back")
            }
        }
    }
}

@Composable
private fun AnswerFeedbackCard(item: AnswerFeedback) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            if (item.questionText != null) {
                Text(
                    text = "Q: ${item.questionText}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (item.whatWorked != null) {
                Text(
                    text = "What worked: ${item.whatWorked}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (item.couldImprove != null) {
                Text(
                    text = "Could improve: ${item.couldImprove}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (item.tryNextTime != null) {
                Text(
                    text = "Try next time: ${item.tryNextTime}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun FeedbackScreenPreview() {
    AIEnglishInterviewTheme {
        FeedbackScreen(
            conversationId = "demo-conversation",
            onBack = {},
            onGoPremium = {},
        )
    }
}
