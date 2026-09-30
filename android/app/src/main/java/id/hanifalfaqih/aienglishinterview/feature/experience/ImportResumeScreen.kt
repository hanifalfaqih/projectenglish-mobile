package id.hanifalfaqih.aienglishinterview.feature.experience

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import id.hanifalfaqih.aienglishinterview.ui.theme.AIEnglishInterviewTheme

/**
 * Slice 2 resume entry: pick a PDF/DOCX, parse via the real backend, then
 * hand off to select-one review. Cancellation stays put; every failure is
 * an explicit error state, never faked content.
 */
@Composable
fun ImportResumeScreen(
    onParsed: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ImportResumeViewModel = viewModel(),
) {
    val uiState = viewModel.uiState
    val context = LocalContext.current

    if (uiState is ImportUiState.Parsed) {
        LaunchedEffect(uiState.count) {
            onParsed()
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult // cancellation: stay put
        val result = readPickedFile(context.contentResolver, uri)
        val bytes = result.getOrNull()
        if (bytes == null) {
            viewModel.onUnreadableFile()
        } else {
            viewModel.parse(bytes, displayName(context.contentResolver, uri))
        }
    }

    val parsing = uiState is ImportUiState.Parsing

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Import from Resume",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = "Choose a PDF or DOCX. We'll extract candidate experiences for you to review — nothing is sent to the interview until you pick one.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(
            onClick = { picker.launch(arrayOf(MIME_PDF, MIME_DOCX)) },
            enabled = !parsing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Choose file")
        }

        if (parsing) {
            Text(
                text = uiState.step,
                style = MaterialTheme.typography.bodySmall,
            )
            CircularProgressIndicator()
        }
        if (uiState is ImportUiState.Error) {
            Text(
                text = uiState.message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onBack, enabled = !parsing) {
                Text("Back")
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ImportResumeScreenPreview() {
    AIEnglishInterviewTheme {
        ImportResumeScreen(onParsed = {}, onBack = {})
    }
}
