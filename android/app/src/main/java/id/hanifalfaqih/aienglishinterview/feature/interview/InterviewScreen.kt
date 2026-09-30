package id.hanifalfaqih.aienglishinterview.feature.interview

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import id.hanifalfaqih.aienglishinterview.core.voice.VoicePhase
import id.hanifalfaqih.aienglishinterview.core.voice.VoiceRecognizer
import id.hanifalfaqih.aienglishinterview.core.voice.VoiceSynthesizer
import id.hanifalfaqih.aienglishinterview.ui.theme.AIEnglishInterviewTheme

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
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = "Interview", style = MaterialTheme.typography.headlineMedium)
        Text(
            text = "status: ${viewModel.status}",
            style = MaterialTheme.typography.bodySmall,
        )

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(viewModel.lines) { line ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = (if (line.isUser) "You: " else "Interviewer: ") + line.text,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        when (viewModel.voicePhase) {
            VoicePhase.LISTENING -> {
                Text(
                    text = "Listening… (55s max — tap Stop when done)",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (viewModel.heardText.isNotBlank()) {
                    Text(
                        text = viewModel.heardText,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            VoicePhase.SPEAKING -> Text(
                text = "Interviewer speaking…",
                style = MaterialTheme.typography.bodyMedium,
            )
            VoicePhase.IDLE -> Unit
        }

        if (viewModel.voiceError != null) {
            Text(
                text = viewModel.voiceError ?: "",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        if (viewModel.voiceNotice != null) {
            Text(
                text = viewModel.voiceNotice ?: "",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        // Transient recognized-answer preview: visible only while its turn
        // is in flight. Once the turn resolves, the transcript lines carry
        // the message and this disappears. Read-only, never editable.
        val pendingPreview = viewModel.pendingVoiceAnswer
        if (viewModel.sending && pendingPreview != null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "You (recognized from speech)",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Text(
                        text = pendingPreview,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }

        if (viewModel.isClosed) {
            Text(text = "Interview complete.", style = MaterialTheme.typography.bodyMedium)
            Button(
                onClick = onCompleteInterview,
                enabled = viewModel.canComplete,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Complete Interview")
            }
        }

        if (viewModel.error != null) {
            Text(
                text = viewModel.error ?: "",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(
                onClick = {
                    if (viewModel.needsOpeningRetry) {
                        viewModel.retryOpening()
                    } else {
                        viewModel.retry()
                    }
                },
            ) {
                Text("Retry")
            }
        }

        if (viewModel.opening) {
            Text(
                text = "Preparing your interview…",
                style = MaterialTheme.typography.bodyMedium,
            )
            CircularProgressIndicator()
        }

        if (viewModel.sending) {
            CircularProgressIndicator()
        }

        if (!viewModel.isClosed) {
            Button(
                onClick = { onMicTap() },
                enabled = micEnabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    when {
                        !viewModel.voiceAvailable -> "Voice engine pending"
                        viewModel.opening -> "Preparing interview…"
                        viewModel.voicePhase == VoicePhase.LISTENING -> "Stop listening"
                        else -> "Tap to speak"
                    },
                )
            }

            OutlinedButton(onClick = onBack) {
                Text("Back")
            }
        } else {
            OutlinedButton(onClick = onBack) {
                Text("Back")
            }
        }
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
