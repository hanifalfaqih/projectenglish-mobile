package id.hanifalfaqih.aienglishinterview.feature.feedback

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import id.hanifalfaqih.aienglishinterview.core.monetization.MonetizationProvider
import id.hanifalfaqih.aienglishinterview.core.monetization.MonetizationRepository
import id.hanifalfaqih.aienglishinterview.core.monetization.PremiumState
import id.hanifalfaqih.aienglishinterview.core.voice.VoicePhase
import id.hanifalfaqih.aienglishinterview.core.voice.VoiceRecognizer
import id.hanifalfaqih.aienglishinterview.data.model.AnswerFeedback
import id.hanifalfaqih.aienglishinterview.data.model.Feedback
import id.hanifalfaqih.aienglishinterview.data.model.Retry
import id.hanifalfaqih.aienglishinterview.data.model.RetryFeedback
import id.hanifalfaqih.aienglishinterview.ui.theme.AIEnglishInterviewTheme

/**
 * Server-owned retry eligibility, read verbatim from
 * [AnswerFeedback.practiceOpportunity]. Never inferred client-side.
 */
internal fun AnswerFeedback.shouldShowRetryAffordance(): Boolean =
    practiceOpportunity

private class FeedbackViewModelFactory(
    private val conversationId: String,
    private val recognizer: VoiceRecognizer? = null,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return FeedbackViewModel(conversationId, recognizer = recognizer) as T
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
    onPracticeAgain: (conversationId: String) -> Unit,
    modifier: Modifier = Modifier,
    recognizer: VoiceRecognizer? = null,
    viewModel: FeedbackViewModel = viewModel(
        factory = FeedbackViewModelFactory(conversationId, recognizer),
    ),
    monetization: MonetizationRepository = MonetizationProvider.repository,
) {
    val context = LocalContext.current
    val premiumState by monetization.premiumState.collectAsState()
    LaunchedEffect(conversationId) {
        monetization.refresh()
    }
    val isPremium = (premiumState as? PremiumState.Determined)?.isPremium == true

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            // Permission granted — if there's a pending practice target, start voice input
            val content = viewModel.uiState as? FeedbackUiState.Content
            val target = viewModel.practiceFormTarget
            if (content != null && target != null) {
                val item = content.feedback.answerItems.find { it.answerMessageId == target }
                if (item != null) {
                    viewModel.startVoiceInput(item)
                }
            }
        } else {
            viewModel.onVoicePermissionDenied()
        }
    }

    fun requestPracticeWithPermission(item: AnswerFeedback) {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            viewModel.startVoiceInput(item)
        } else {
            // Set the form target first so the permission callback knows what to start
            viewModel.requestPractice(item)
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
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
                    retryStates = viewModel.retryStates,
                    practiceFormTarget = viewModel.practiceFormTarget,
                    voicePhase = viewModel.voicePhase,
                    voiceError = viewModel.voiceError,
                    onGoPremium = onGoPremium,
                    onBack = onBack,
                    onRequestPractice = { item -> requestPracticeWithPermission(item) },
                    onClosePractice = { viewModel.cancelVoiceInput() },
                    onRegenerateRetry = { item -> viewModel.regenerateRetry(item.answerMessageId) },
                    modifier = Modifier.weight(1f),
                )
                PracticeAgainRow(
                    state = viewModel.practiceAgainState,
                    onPracticeAgain = { viewModel.practiceAgain() },
                )
            }
        }

        val again = viewModel.practiceAgainState
        if (again is PracticeAgainState.Done) {
            LaunchedEffect(again.conversationId) {
                onPracticeAgain(again.conversationId)
            }
        }
        if (again is PracticeAgainState.RequiresPurchase) {
            LaunchedEffect(Unit) {
                onGoPremium()
                viewModel.onPaywallNavigated()
            }
        }
        // M12 gate: eligible item + no premium access → existing paywall.
        if (viewModel.retryGateRequiresPurchase) {
            LaunchedEffect(Unit) {
                onGoPremium()
                viewModel.onRetryGateNavigated()
            }
        }
    }
}

@Composable
private fun PracticeAgainRow(
    state: PracticeAgainState,
    onPracticeAgain: () -> Unit,
) {
    when (state) {
        is PracticeAgainState.Working -> {
            CircularProgressIndicator()
            Text(
                text = "Starting a new interview…",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        is PracticeAgainState.Error -> {
            Text(
                text = state.message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onPracticeAgain) {
                Text("Try Again")
            }
        }
        else -> {
            OutlinedButton(
                onClick = onPracticeAgain,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Practice Again")
            }
        }
    }
}

@Composable
private fun FeedbackContent(
    feedback: Feedback,
    isPremium: Boolean,
    retryStates: Map<String, AnswerRetryState>,
    practiceFormTarget: String?,
    voicePhase: VoicePhase,
    voiceError: String?,
    onGoPremium: () -> Unit,
    onBack: () -> Unit,
    onRequestPractice: (AnswerFeedback) -> Unit,
    onClosePractice: () -> Unit,
    onRegenerateRetry: (AnswerFeedback) -> Unit,
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
                items(
                    items = feedback.answerItems,
                    key = { it.answerMessageId },
                ) { item ->
                    AnswerFeedbackCard(
                        item = item,
                        retryState = retryStates[item.answerMessageId]
                            ?: AnswerRetryState.Idle,
                        formOpen = practiceFormTarget == item.answerMessageId,
                        voicePhase = if (practiceFormTarget == item.answerMessageId) voicePhase else VoicePhase.IDLE,
                        voiceError = if (practiceFormTarget == item.answerMessageId) voiceError else null,
                        onRequestPractice = onRequestPractice,
                        onClosePractice = onClosePractice,
                        onRegenerateRetry = { onRegenerateRetry(item) },
                    )
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
            // M12 teaser: eligible answers surface the targeted-practice
            // affordance to non-premium users too; tapping routes through
            // the existing premium gate to the existing paywall. Ineligible
            // items never appear here (no upsell for them).
            val eligible = feedback.answerItems.filter { it.shouldShowRetryAffordance() }
            if (eligible.isNotEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = "Targeted practice",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            eligible.forEach { entry ->
                                if (entry.questionText != null) {
                                    Text(
                                        text = "Q: ${entry.questionText}",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                OutlinedButton(onClick = { onRequestPractice(entry) }) {
                                    Text("Practice this")
                                }
                            }
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
private fun AnswerFeedbackCard(
    item: AnswerFeedback,
    retryState: AnswerRetryState,
    formOpen: Boolean,
    voicePhase: VoicePhase,
    voiceError: String?,
    onRequestPractice: (AnswerFeedback) -> Unit,
    onClosePractice: () -> Unit,
    onRegenerateRetry: () -> Unit,
) {
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

            if (item.shouldShowRetryAffordance()) {
                TargetedPracticeSection(
                    retryState = retryState,
                    formOpen = formOpen,
                    voicePhase = voicePhase,
                    voiceError = voiceError,
                    onPracticeThis = { onRequestPractice(item) },
                    onCancel = onClosePractice,
                    onRegenerate = onRegenerateRetry,
                )
            }
        }
    }
}

/**
 * M12 inline retry interaction for ONE feedback item. The original feedback
 * above stays visible and immutable; retry content renders below it as a
 * separate section. Distinctly labeled from M15 "Practice Again".
 */
@Composable
private fun TargetedPracticeSection(
    retryState: AnswerRetryState,
    formOpen: Boolean,
    voicePhase: VoicePhase,
    voiceError: String?,
    onPracticeThis: () -> Unit,
    onCancel: () -> Unit,
    onRegenerate: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Targeted Practice",
            style = MaterialTheme.typography.titleSmall,
        )
        when (retryState) {
            is AnswerRetryState.Submitting -> {
                CircularProgressIndicator()
                Text(
                    text = "Submitting retry…",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            is AnswerRetryState.FeedbackAvailable -> {
                RetryFeedbackContent(retry = retryState.retry)
            }
            is AnswerRetryState.FeedbackFailed -> {
                Text(
                    text = "Retry feedback could not be generated.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = onRegenerate) {
                    Text("Regenerate feedback")
                }
            }
            is AnswerRetryState.Error -> {
                Text(
                    text = retryState.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            is AnswerRetryState.Idle -> Unit
        }
        // Voice-only retry: no text draft, just record and submit
        if (retryState !is AnswerRetryState.Submitting) {
            if (formOpen) {
                RetryInputForm(
                    voicePhase = voicePhase,
                    voiceError = voiceError,
                    onCancel = onCancel,
                    onStartRecording = onPracticeThis,
                    onStopRecording = onCancel,
                )
            } else if (retryState is AnswerRetryState.Idle) {
                OutlinedButton(onClick = onPracticeThis) {
                    Text("Practice this")
                }
            } else if (retryState is AnswerRetryState.Error) {
                TextButton(onClick = onPracticeThis) {
                    Text("Try Again")
                }
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
            onPracticeAgain = {},
        )
    }
}

private fun previewItem(practice: Boolean) = AnswerFeedback(
    answerMessageId = "u1",
    questionMessageId = "a1",
    questionText = "Tell me about a challenge?",
    whatWorked = null,
    couldImprove = "Explain the challenge before the solution.",
    tryNextTime = "Use STAR.",
    practiceOpportunity = practice,
)

private fun previewRetry() = Retry(
    id = "r1",
    conversationId = "conv-1",
    answerMessageId = "u1",
    questionMessageId = "a1",
    retryClientKey = "key-1",
    retryAnswer = "Better answer.",
    feedback = RetryFeedback(
        overall = "Clearer now.",
        whatWorked = "STAR structure.",
        couldImprove = null,
        tryNextTime = "Add metrics.",
        professionalCommunication = null,
    ),
    feedbackStatus = "generated",
    feedbackPromptVersion = "retry-feedback-1.0.0",
    originalQuestion = "Tell me about a challenge?",
    originalAnswer = "Compression was hard.",
    createdAt = "",
    updatedAt = "",
)

@Preview(showBackground = true)
@Composable
private fun RetryAffordancePreview() {
    AIEnglishInterviewTheme {
        AnswerFeedbackCard(
            item = previewItem(practice = true),
            retryState = AnswerRetryState.Idle,
            formOpen = false,
            voicePhase = VoicePhase.IDLE,
            voiceError = null,
            onRequestPractice = {},
            onClosePractice = {},
            onRegenerateRetry = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RetryFeedbackPreview() {
    AIEnglishInterviewTheme {
        AnswerFeedbackCard(
            item = previewItem(practice = true),
            retryState = AnswerRetryState.FeedbackAvailable(previewRetry()),
            formOpen = false,
            voicePhase = VoicePhase.IDLE,
            voiceError = null,
            onRequestPractice = {},
            onClosePractice = {},
            onRegenerateRetry = {},
        )
    }
}

@Composable
private fun RetryInputForm(
    voicePhase: VoicePhase,
    voiceError: String?,
    onCancel: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = when (voicePhase) {
                VoicePhase.LISTENING -> "Listening… (tap Stop when done)"
                VoicePhase.SPEAKING -> "Processing…"
                VoicePhase.IDLE -> "Speak your answer"
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        if (voiceError != null) {
            Text(
                text = voiceError,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (voicePhase) {
                VoicePhase.LISTENING -> {
                    OutlinedButton(
                        onClick = onStopRecording,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Stop")
                    }
                }
                VoicePhase.SPEAKING -> {
                    OutlinedButton(
                        onClick = {},
                        modifier = Modifier.weight(1f),
                        enabled = false,
                    ) {
                        Text("Processing…")
                    }
                }
                VoicePhase.IDLE -> {
                    OutlinedButton(
                        onClick = onStartRecording,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Record")
                    }
                }
            }
            TextButton(
                onClick = onCancel,
                enabled = voicePhase != VoicePhase.SPEAKING,
            ) {
                Text("Cancel")
            }
        }
    }
}

/**
 * Retry feedback as its own section below the immutable original.
 * Qualitative backend content only — no scores, comparisons, or labels.
 */
@Composable
private fun RetryFeedbackContent(retry: Retry) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "Retry Feedback",
            style = MaterialTheme.typography.titleSmall,
        )
        val feedback: RetryFeedback = retry.feedback ?: return
        Text(
            text = feedback.overall,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (feedback.whatWorked != null) {
            Text(
                text = "What worked: ${feedback.whatWorked}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (feedback.couldImprove != null) {
            Text(
                text = "Could improve: ${feedback.couldImprove}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (feedback.tryNextTime != null) {
            Text(
                text = "Try next time: ${feedback.tryNextTime}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        feedback.professionalCommunication?.forEach { point ->
            Text(
                text = "• $point",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
