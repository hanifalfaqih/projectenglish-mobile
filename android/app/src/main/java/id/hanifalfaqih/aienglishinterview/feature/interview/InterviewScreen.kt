package id.hanifalfaqih.aienglishinterview.feature.interview

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import id.hanifalfaqih.aienglishinterview.R
import id.hanifalfaqih.aienglishinterview.core.voice.VoicePhase
import id.hanifalfaqih.aienglishinterview.core.voice.VoiceRecognizer
import id.hanifalfaqih.aienglishinterview.core.voice.VoiceSynthesizer
import id.hanifalfaqih.aienglishinterview.ui.components.BannerTone
import id.hanifalfaqih.aienglishinterview.ui.components.Hairline
import id.hanifalfaqih.aienglishinterview.ui.components.QuietAction
import id.hanifalfaqih.aienglishinterview.ui.components.SecondaryButton
import id.hanifalfaqih.aienglishinterview.ui.components.StatusBanner
import id.hanifalfaqih.aienglishinterview.ui.theme.AIEnglishInterviewTheme
import id.hanifalfaqih.aienglishinterview.ui.theme.Error
import id.hanifalfaqih.aienglishinterview.ui.theme.Muted
import id.hanifalfaqih.aienglishinterview.ui.theme.PrimaryBlue
import id.hanifalfaqih.aienglishinterview.ui.theme.OnAccentFill
import id.hanifalfaqih.aienglishinterview.ui.theme.SecondaryTeal

private class InterviewViewModelFactory(
    private val conversationId: String,
    private val recognizer: VoiceRecognizer? = null,
    private val synthesizer: VoiceSynthesizer? = null,
    private val audioPlayer: VoiceSynthesizer? = null,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return InterviewViewModel(
            conversationId,
            recognizer = recognizer,
            synthesizer = synthesizer,
            audioPlayer = audioPlayer,
        ) as T
    }
}

/**
 * Voice-first interview screen. AI speaks first: on entry the interviewer
 * opening is generated and auto-played; the microphone ("Tap to speak")
 * stays disabled until it finishes.
 *
 * Presentation: a clean transcript on a neutral canvas — the interviewer's
 * message is the page's primary content (label + prose, no avatar chrome),
 * the candidate's answer is a quiet tinted block, and one unmistakable mic
 * CTA anchors the bottom. Voice phase and error semantics unchanged.
 */
@Composable
fun InterviewScreen(
    conversationId: String,
    onCompleteInterview: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    recognizer: VoiceRecognizer? = null,
    synthesizer: VoiceSynthesizer? = null,
    audioPlayer: VoiceSynthesizer? = null,
    viewModel: InterviewViewModel = viewModel(
        factory = InterviewViewModelFactory(conversationId, recognizer, synthesizer, audioPlayer),
    ),
) {
    val context = LocalContext.current
    var lastMicTapMs by remember { mutableLongStateOf(0L) }

    DisposableEffect(conversationId) {
        onDispose { viewModel.releaseVoice() }
    }

    // AI-first lifecycle: the interviewer speaks first. Guarded inside the
    // ViewModel (plus server-side replay), so re-entry is safe.
    LaunchedEffect(conversationId) {
        viewModel.startOpening()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            viewModel.startVoiceInput()
        } else {
            viewModel.onVoicePermissionDenied()
        }
    }

    fun onMicTap() {
        if (!viewModel.voiceAvailable || viewModel.isClosed) return
        // Bounce guard: a duplicated tap event (observed via adb/emulator
        // input) arriving milliseconds after a stop would otherwise cancel
        // and instantly restart recording, stranding a phantom session.
        // Human taps are far slower than this window; the stale-session
        // guards remain the backstop.
        val now = android.os.SystemClock.uptimeMillis()
        if (!shouldAcceptMicTap(lastMicTapMs, now)) return
        lastMicTapMs = now
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            if (viewModel.voicePhase == VoicePhase.LISTENING) {
                viewModel.cancelVoiceInput()
            } else {
                viewModel.startVoiceInput()
            }
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val micEnabled = viewModel.voiceAvailable &&
        !viewModel.isClosed &&
        !viewModel.sending &&
        !viewModel.opening &&
        viewModel.voicePhase != VoicePhase.SPEAKING

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // Quiet header: state lives here, not in a colored band.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "INTERVIEW",
                    style = MaterialTheme.typography.labelMedium,
                    color = Muted,
                )
                Text(
                    text = if (viewModel.isClosed) "Complete" else "In progress",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (viewModel.isClosed) SecondaryTeal else PrimaryBlue,
                )
            }
            Text(
                text = "Project English",
                style = MaterialTheme.typography.labelMedium,
                color = Muted,
            )
        }
        Hairline()

        // Conversation transcript
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            items(viewModel.lines) { line ->
                if (line.isUser) {
                    UserLine(text = line.text)
                } else {
                    InterviewerLine(text = line.text)
                }
            }
        }

        // Voice status and controls dock
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Voice phase indicator — one compact line, not a card
            when (viewModel.voicePhase) {
                VoicePhase.LISTENING -> VoiceHintLine(
                    text = if (viewModel.heardText.isNotBlank()) {
                        "Listening… “${viewModel.heardText}”"
                    } else {
                        "Listening — 55s max, tap Stop when done"
                    },
                    color = Error,
                )
                VoicePhase.SPEAKING -> VoiceHintLine(
                    text = "Interviewer speaking…",
                    color = SecondaryTeal,
                )
                VoicePhase.IDLE -> if (!viewModel.isClosed && !viewModel.opening) {
                    VoiceHintLine(text = "Ready to record", color = Muted)
                }
                VoicePhase.FINALIZING -> Unit // Not used in InterviewScreen
            }

            // Pending voice answer preview
            val pendingPreview = viewModel.pendingVoiceAnswer
            if (viewModel.sending && pendingPreview != null) {
                VoiceHintLine(text = "You said: “$pendingPreview”", color = PrimaryBlue)
            }

            if (viewModel.voiceError != null) {
                StatusBanner(text = viewModel.voiceError ?: "", tone = BannerTone.Error)
            }
            if (viewModel.voiceNotice != null) {
                StatusBanner(text = viewModel.voiceNotice ?: "", tone = BannerTone.Info)
            }

            // Completion state
            if (viewModel.isClosed) {
                StatusBanner(text = "Interview complete", tone = BannerTone.Success)
                Button(
                    onClick = onCompleteInterview,
                    enabled = viewModel.canComplete,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SecondaryTeal),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Complete Interview", style = MaterialTheme.typography.labelLarge)
                }
            }

            // Error state
            if (viewModel.error != null) {
                StatusBanner(
                    text = viewModel.error ?: "",
                    tone = BannerTone.Error,
                    actionLabel = "Retry",
                    onAction = {
                        if (viewModel.needsOpeningRetry) {
                            viewModel.retryOpening()
                        } else {
                            viewModel.retry()
                        }
                    },
                )
            }

            // Loading states
            if (viewModel.opening) {
                StatusBanner(text = "Preparing your interview…", tone = BannerTone.Progress, spinning = true)
            }
            if (viewModel.sending) {
                StatusBanner(text = "Processing your response…", tone = BannerTone.Progress, spinning = true)
            }

            // The single, unmistakable voice action.
            if (!viewModel.isClosed) {
                Button(
                    onClick = { onMicTap() },
                    enabled = micEnabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = when {
                            viewModel.voicePhase == VoicePhase.LISTENING -> Error
                            !viewModel.voiceAvailable -> Color.Gray
                            else -> PrimaryBlue
                        },
                    ),
                    shape = RoundedCornerShape(14.dp),
                    elevation = ButtonDefaults.buttonElevation(
                        defaultElevation = 0.dp,
                        pressedElevation = 0.dp,
                    ),
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_mic),
                        contentDescription = null,
                        tint = OnAccentFill,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = when {
                            !viewModel.voiceAvailable -> "Voice engine pending"
                            viewModel.opening -> "Preparing interview…"
                            viewModel.voicePhase == VoicePhase.LISTENING -> "Stop listening"
                            else -> "Tap to speak"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                SecondaryButton(text = "Back", onClick = onBack, modifier = Modifier.fillMaxWidth())
            } else {
                QuietAction(text = "Back", onClick = onBack, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/** The interviewer's message is primary page content: label, then prose. */
@Composable
private fun InterviewerLine(text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "INTERVIEWER",
            style = MaterialTheme.typography.labelMedium,
            color = Muted,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** The candidate's answer reads as a quiet tinted block, right-aligned. */
@Composable
private fun UserLine(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = 16.dp,
                        bottomEnd = 4.dp,
                    ),
                )
                .background(PrimaryBlue.copy(alpha = 0.07f))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "YOU",
                style = MaterialTheme.typography.labelSmall,
                color = Muted,
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun VoiceHintLine(text: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color),
        )
        Text(text = text, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

@Preview(showBackground = true)
@Composable
private fun InterviewScreenPreview() {
    AIEnglishInterviewTheme {
        InterviewScreen(
            conversationId = "demo-conversation",
            onCompleteInterview = {},
            onBack = {},
        )
    }
}

/** Minimum gap between accepted microphone taps. */
internal const val MIC_TAP_DEBOUNCE_MS = 300L

/** Pure mic-tap debounce decision, unit-tested below. */
internal fun shouldAcceptMicTap(lastTapMs: Long, nowMs: Long): Boolean =
    nowMs - lastTapMs >= MIC_TAP_DEBOUNCE_MS
