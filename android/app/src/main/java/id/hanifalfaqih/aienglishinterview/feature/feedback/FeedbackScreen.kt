package id.hanifalfaqih.aienglishinterview.feature.feedback

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import id.hanifalfaqih.aienglishinterview.R
import id.hanifalfaqih.aienglishinterview.core.monetization.MonetizationProvider
import id.hanifalfaqih.aienglishinterview.core.monetization.MonetizationRepository
import id.hanifalfaqih.aienglishinterview.core.monetization.PremiumState
import id.hanifalfaqih.aienglishinterview.core.voice.VoicePhase
import id.hanifalfaqih.aienglishinterview.core.voice.VoiceRecognizer
import id.hanifalfaqih.aienglishinterview.data.model.AnswerFeedback
import id.hanifalfaqih.aienglishinterview.data.model.Feedback
import id.hanifalfaqih.aienglishinterview.data.model.Retry
import id.hanifalfaqih.aienglishinterview.data.model.RetryFeedback
import id.hanifalfaqih.aienglishinterview.ui.components.BannerTone
import id.hanifalfaqih.aienglishinterview.ui.components.Hairline
import id.hanifalfaqih.aienglishinterview.ui.components.PrimaryButton
import id.hanifalfaqih.aienglishinterview.ui.components.QuietAction
import id.hanifalfaqih.aienglishinterview.ui.components.ScreenSubtitle
import id.hanifalfaqih.aienglishinterview.ui.components.ScreenTitle
import id.hanifalfaqih.aienglishinterview.ui.components.SecondaryButton
import id.hanifalfaqih.aienglishinterview.ui.components.SectionLabel
import id.hanifalfaqih.aienglishinterview.ui.components.StatusBanner
import id.hanifalfaqih.aienglishinterview.ui.theme.AIEnglishInterviewTheme
import id.hanifalfaqih.aienglishinterview.ui.theme.Error
import id.hanifalfaqih.aienglishinterview.ui.theme.Muted
import id.hanifalfaqih.aienglishinterview.ui.theme.PrimaryBlue
import id.hanifalfaqih.aienglishinterview.ui.theme.SecondaryTeal

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
 *
 * Presentation: one scannable editorial document — overall at the top,
 * per-answer sections separated by hairlines (not floating cards), then
 * the single practice action. Entitlement semantics unchanged.
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
        // Check entitlement first - only premium users should see permission dialog
        when (val state = monetization.premiumState.value) {
            is PremiumState.Determined -> {
                if (!state.isPremium) {
                    // Free user - route to paywall, no permission request
                    viewModel.requestPractice(item)
                    return
                }
                // Premium user - check permission
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
            is PremiumState.Loading, is PremiumState.Unavailable -> {
                // Unresolved entitlement - show error, no permission request
                viewModel.requestPractice(item)
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
    ) {
        Spacer(modifier = Modifier.height(40.dp))
        ScreenTitle("Interview Feedback")
        Spacer(modifier = Modifier.height(8.dp))
        ScreenSubtitle("Here's how you did")
        Spacer(modifier = Modifier.height(24.dp))

        when (val state = viewModel.uiState) {
            is FeedbackUiState.Loading -> {
                StatusBanner(
                    text = "Generating your feedback…",
                    tone = BannerTone.Progress,
                    spinning = true,
                )
            }
            is FeedbackUiState.Error -> {
                StatusBanner(
                    text = state.message,
                    tone = BannerTone.Error,
                    actionLabel = "Retry",
                    onAction = { viewModel.retry() },
                )
                Spacer(modifier = Modifier.height(16.dp))
                SecondaryButton(text = "Back", onClick = onBack, modifier = Modifier.fillMaxWidth())
            }
            is FeedbackUiState.Content -> {
                FeedbackContent(
                    feedback = state.feedback,
                    premiumState = premiumState,
                    retryStates = viewModel.retryStates,
                    practiceFormTarget = viewModel.practiceFormTarget,
                    voicePhase = viewModel.voicePhase,
                    voiceError = viewModel.voiceError,
                    captureElapsedMs = viewModel.captureElapsedMs,
                    onGoPremium = onGoPremium,
                    onBack = onBack,
                    onRequestPractice = { item -> requestPracticeWithPermission(item) },
                    onClosePractice = { viewModel.cancelVoiceInput() },
                    onFinishRecording = { viewModel.finishVoiceInput() },
                    onRegenerateRetry = { item -> viewModel.regenerateRetry(item.answerMessageId) },
                    onRestoreRetry = { item -> viewModel.loadCurrentRetry(item.answerMessageId) },
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

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun PracticeAgainRow(
    state: PracticeAgainState,
    onPracticeAgain: () -> Unit,
) {
    when (state) {
        is PracticeAgainState.Working -> {
            Spacer(modifier = Modifier.height(16.dp))
            StatusBanner(
                text = "Starting a new interview…",
                tone = BannerTone.Progress,
                spinning = true,
            )
        }
        is PracticeAgainState.Error -> {
            Spacer(modifier = Modifier.height(16.dp))
            StatusBanner(
                text = state.message,
                tone = BannerTone.Error,
                actionLabel = "Try Again",
                onAction = onPracticeAgain,
            )
        }
        else -> {
            Spacer(modifier = Modifier.height(24.dp))
            PrimaryButton(
                text = "Practice Again",
                onClick = onPracticeAgain,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun FeedbackContent(
    feedback: Feedback,
    premiumState: PremiumState,
    retryStates: Map<String, AnswerRetryState>,
    practiceFormTarget: String?,
    voicePhase: VoicePhase,
    voiceError: String?,
    captureElapsedMs: Long,
    onGoPremium: () -> Unit,
    onBack: () -> Unit,
    onRequestPractice: (AnswerFeedback) -> Unit,
    onClosePractice: () -> Unit,
    onFinishRecording: () -> Unit,
    onRegenerateRetry: (AnswerFeedback) -> Unit,
    onRestoreRetry: (AnswerFeedback) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isPremium = (premiumState as? PremiumState.Determined)?.isPremium == true
    val isFree = premiumState is PremiumState.Determined && !premiumState.isPremium
    val isChecking = premiumState is PremiumState.Loading
    val isUnavailable = premiumState is PremiumState.Unavailable

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // Overall — the headline of the document, not a colored panel.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("Overall")
            Text(
                text = feedback.overall,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Hairline()

        if (isPremium) {
            // Premium users see full feedback
            if (feedback.answerItems.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    SectionLabel(
                        "Answer Feedback",
                        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                    )
                    feedback.answerItems.forEach { item ->
                        Hairline()
                        AnswerFeedbackSection(
                            item = item,
                            retryState = retryStates[item.answerMessageId]
                                ?: AnswerRetryState.Idle,
                            formOpen = practiceFormTarget == item.answerMessageId,
                            voicePhase = if (practiceFormTarget == item.answerMessageId) voicePhase else VoicePhase.IDLE,
                            voiceError = if (practiceFormTarget == item.answerMessageId) voiceError else null,
                            captureElapsedMs = if (practiceFormTarget == item.answerMessageId) captureElapsedMs else 0L,
                            onRequestPractice = onRequestPractice,
                            onClosePractice = onClosePractice,
                            onFinishRecording = onFinishRecording,
                            onRegenerateRetry = { onRegenerateRetry(item) },
                            onRestoreRetry = { onRestoreRetry(item) },
                        )
                        Hairline()
                    }
                }
            }

            if (feedback.professionalCommunication.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionLabel("Professional Communication")
                    feedback.professionalCommunication.forEach { point ->
                        Row(
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .padding(top = 8.dp)
                                    .size(4.dp)
                                    .clip(CircleShape)
                                    .background(Muted),
                            )
                            Text(
                                text = point,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        } else if (isFree) {
            // Free users see premium teaser + targeted practice teaser
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("Go Further")
                Text(
                    text = "Per-answer breakdown and professional communication coaching are premium features.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted,
                )
                PrimaryButton(
                    text = "Unlock with Premium",
                    onClick = onGoPremium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
            }
            // M12 teaser: eligible answers surface the targeted-practice
            // affordance to non-premium users too; tapping routes through
            // the existing premium gate to the existing paywall. Ineligible
            // items never appear here (no upsell for them).
            val eligible = feedback.answerItems.filter { it.shouldShowRetryAffordance() }
            if (eligible.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionLabel("Targeted Practice")
                    eligible.forEach { entry ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (entry.questionText != null) {
                                Text(
                                    text = entry.questionText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            SecondaryButton(
                                text = "Practice this",
                                onClick = { onRequestPractice(entry) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        } else if (isChecking) {
            // Checking entitlement - neutral state, no upsell
            StatusBanner(text = "Checking your access…", tone = BannerTone.Info, spinning = true)
        } else if (isUnavailable) {
            // Unavailable entitlement - neutral error state, no upsell
            StatusBanner(
                text = "Unable to check your access. Please check your internet connection and try again.",
                tone = BannerTone.Error,
            )
        }

        // Timestamp and back — quiet footer.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Generated ${feedback.createdAt}",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )
            QuietAction(text = "Back", onClick = onBack)
        }
    }
}

@Composable
private fun AnswerFeedbackSection(
    item: AnswerFeedback,
    retryState: AnswerRetryState,
    formOpen: Boolean,
    voicePhase: VoicePhase,
    voiceError: String?,
    captureElapsedMs: Long,
    onRequestPractice: (AnswerFeedback) -> Unit,
    onClosePractice: () -> Unit,
    onFinishRecording: () -> Unit,
    onRegenerateRetry: () -> Unit,
    onRestoreRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (item.questionText != null) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SectionLabel("Question")
                Text(
                    text = item.questionText,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        if (item.whatWorked != null) {
            FeedbackRow(label = "What worked", content = item.whatWorked, accent = SecondaryTeal)
        }
        if (item.couldImprove != null) {
            FeedbackRow(label = "Could improve", content = item.couldImprove, accent = PrimaryBlue)
        }
        if (item.tryNextTime != null) {
            FeedbackRow(label = "Try next time", content = item.tryNextTime, accent = Muted)
        }

        if (item.shouldShowRetryAffordance()) {
            TargetedPracticeSection(
                retryState = retryState,
                formOpen = formOpen,
                voicePhase = voicePhase,
                voiceError = voiceError,
                captureElapsedMs = captureElapsedMs,
                onPracticeThis = { onRequestPractice(item) },
                onCancel = onClosePractice,
                onFinishRecording = onFinishRecording,
                onRegenerate = onRegenerateRetry,
                onRestore = onRestoreRetry,
            )
        }
    }
}

@Composable
private fun FeedbackRow(label: String, content: String, accent: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = label.uppercase(), style = MaterialTheme.typography.labelMedium, color = accent)
        Text(
            text = content,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
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
    captureElapsedMs: Long,
    onPracticeThis: () -> Unit,
    onCancel: () -> Unit,
    onFinishRecording: () -> Unit,
    onRegenerate: () -> Unit,
    onRestore: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionLabel("Targeted Practice")

        when (retryState) {
            is AnswerRetryState.Submitting -> {
                StatusBanner(text = "Submitting retry…", tone = BannerTone.Progress, spinning = true)
            }
            is AnswerRetryState.FeedbackAvailable -> {
                RetryFeedbackContent(retry = retryState.retry)
            }
            is AnswerRetryState.FeedbackFailed -> {
                StatusBanner(
                    text = "Retry feedback could not be generated.",
                    tone = BannerTone.Error,
                    actionLabel = "Regenerate feedback",
                    onAction = onRegenerate,
                )
            }
            is AnswerRetryState.Error -> {
                StatusBanner(text = retryState.message, tone = BannerTone.Error)
            }
            is AnswerRetryState.Unrecoverable -> {
                StatusBanner(text = retryState.message, tone = BannerTone.Error)
                // No Record button - this cannot be retried
            }
            is AnswerRetryState.RestoreFailed -> {
                StatusBanner(
                    text = retryState.message,
                    tone = BannerTone.Error,
                    actionLabel = "Try Again",
                    onAction = onRestore,
                )
            }
            is AnswerRetryState.Idle -> Unit
        }

        // Voice-only retry: no text draft, just record and submit
        if (retryState !is AnswerRetryState.Submitting && retryState !is AnswerRetryState.Unrecoverable && retryState !is AnswerRetryState.RestoreFailed) {
            if (formOpen) {
                RetryInputForm(
                    voicePhase = voicePhase,
                    voiceError = voiceError,
                    captureElapsedMs = captureElapsedMs,
                    onCancel = onCancel,
                    onStartRecording = onPracticeThis,
                    onStopRecording = onFinishRecording,
                )
            } else if (retryState is AnswerRetryState.Idle) {
                PrimaryButton(
                    text = "Practice this",
                    onClick = onPracticeThis,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else if (retryState is AnswerRetryState.Error) {
                QuietAction(
                    text = "Try Again",
                    onClick = onPracticeThis,
                    modifier = Modifier.fillMaxWidth(),
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
        AnswerFeedbackSection(
            item = previewItem(practice = true),
            retryState = AnswerRetryState.Idle,
            formOpen = false,
            voicePhase = VoicePhase.IDLE,
            voiceError = null,
            captureElapsedMs = 0L,
            onRequestPractice = {},
            onClosePractice = {},
            onFinishRecording = {},
            onRegenerateRetry = {},
            onRestoreRetry = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun RetryFeedbackPreview() {
    AIEnglishInterviewTheme {
        AnswerFeedbackSection(
            item = previewItem(practice = true),
            retryState = AnswerRetryState.FeedbackAvailable(previewRetry()),
            formOpen = false,
            voicePhase = VoicePhase.IDLE,
            voiceError = null,
            captureElapsedMs = 0L,
            onRequestPractice = {},
            onClosePractice = {},
            onFinishRecording = {},
            onRegenerateRetry = {},
            onRestoreRetry = {},
        )
    }
}

@Composable
private fun RetryInputForm(
    voicePhase: VoicePhase,
    voiceError: String?,
    captureElapsedMs: Long,
    onCancel: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
) {
    val statusText = when (voicePhase) {
        VoicePhase.LISTENING -> "Listening…"
        VoicePhase.FINALIZING -> "Finishing your answer…"
        VoicePhase.SPEAKING -> "Processing…"
        VoicePhase.IDLE -> "Ready to record"
    }
    val statusTone = when (voicePhase) {
        VoicePhase.LISTENING -> BannerTone.Progress
        VoicePhase.FINALIZING -> BannerTone.Progress
        else -> BannerTone.Info
    }
    StatusBanner(
        text = if (voicePhase == VoicePhase.LISTENING || voicePhase == VoicePhase.FINALIZING) {
            val elapsedSeconds = (captureElapsedMs / 1000).toInt()
            "$statusText ${elapsedSeconds}s / 55s"
        } else {
            statusText
        },
        tone = statusTone,
        spinning = voicePhase == VoicePhase.SPEAKING,
    )
    if (voiceError != null) {
        StatusBanner(text = voiceError, tone = BannerTone.Error)
    }

    // FINALIZING phase: no controls shown
    if (voicePhase != VoicePhase.FINALIZING) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            when (voicePhase) {
                VoicePhase.LISTENING -> {
                    Button(
                        onClick = onStopRecording,
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Error),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("Stop", style = MaterialTheme.typography.labelLarge)
                    }
                }
                VoicePhase.SPEAKING -> {
                    Button(
                        onClick = {},
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp),
                        enabled = false,
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("Processing…", style = MaterialTheme.typography.labelLarge)
                    }
                }
                VoicePhase.IDLE -> {
                    PrimaryButton(
                        text = "Record",
                        onClick = onStartRecording,
                        modifier = Modifier.weight(1f),
                    )
                }
                VoicePhase.FINALIZING -> { /* No button in FINALIZING */ }
            }
            SecondaryButton(
                text = "Cancel",
                onClick = onCancel,
                enabled = voicePhase != VoicePhase.SPEAKING,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * Retry feedback as its own section below the immutable original.
 * Qualitative backend content only — no scores, comparisons, or labels.
 */
@Composable
private fun RetryFeedbackContent(retry: Retry) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SectionLabel("Retry Feedback")
        val feedback: RetryFeedback? = retry.feedback
        if (feedback != null) {
            Text(
                text = feedback.overall,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (feedback.whatWorked != null) {
                FeedbackRow(label = "What worked", content = feedback.whatWorked, accent = SecondaryTeal)
            }
            if (feedback.couldImprove != null) {
                FeedbackRow(label = "Could improve", content = feedback.couldImprove, accent = PrimaryBlue)
            }
            if (feedback.tryNextTime != null) {
                FeedbackRow(label = "Try next time", content = feedback.tryNextTime, accent = Muted)
            }
            if (!feedback.professionalCommunication.isNullOrEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    feedback.professionalCommunication.forEach { point ->
                        Text(
                            text = "• $point",
                            style = MaterialTheme.typography.bodySmall,
                            color = Muted,
                        )
                    }
                }
            }
        }
    }
}
