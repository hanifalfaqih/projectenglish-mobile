package id.hanifalfaqih.aienglishinterview.feature.interview

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import id.hanifalfaqih.aienglishinterview.core.voice.VoicePhase
import id.hanifalfaqih.aienglishinterview.core.voice.VoiceRecognizer
import id.hanifalfaqih.aienglishinterview.core.voice.VoiceSynthesizer
import id.hanifalfaqih.aienglishinterview.ui.theme.AIEnglishInterviewTheme

private class InterviewViewModelFactory(
    private val conversationId: String,
    private val recognizer: VoiceRecognizer? = null,
    private val synthesizer: VoiceSynthesizer? = null,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return InterviewViewModel(conversationId, recognizer = recognizer, synthesizer = synthesizer) as T
    }
}

/**
 * Voice interview screen. The microphone is the primary interaction once a
 * voice provider is wired; until then it stays disabled with a pending
 * notice. The DEV text field remains only as a backend-contract bridge.
 */
@Composable
fun InterviewScreen(
    conversationId: String,
    onCompleteInterview: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    recognizer: VoiceRecognizer? = null,
    synthesizer: VoiceSynthesizer? = null,
    viewModel: InterviewViewModel = viewModel(
        factory = InterviewViewModelFactory(conversationId, recognizer, synthesizer),
    ),
) {
    val context = LocalContext.current
    var draft by remember { mutableStateOf("") }

    DisposableEffect(conversationId) {
        onDispose { viewModel.releaseVoice() }
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
            VoicePhase.LISTENING -> Text(
                text = "Listening… ${viewModel.heardText}",
                style = MaterialTheme.typography.bodyMedium,
            )
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
            Button(onClick = onCompleteInterview, modifier = Modifier.fillMaxWidth()) {
                Text("Complete Interview")
            }
        }

        if (viewModel.error != null) {
            Text(
                text = viewModel.error ?: "",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = { viewModel.retry() }) {
                Text("Retry")
            }
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
                        viewModel.voicePhase == VoicePhase.LISTENING -> "Stop listening"
                        else -> "Tap to speak"
                    },
                )
            }

            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text("DEV answer input (voice comes later)") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !viewModel.sending,
                minLines = 2,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onBack) {
                    Text("Back")
                }
                Button(
                    onClick = {
                        viewModel.send(draft)
                        draft = ""
                    },
                    enabled = !viewModel.sending && draft.isNotBlank(),
                ) {
                    Text("Send")
                }
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
