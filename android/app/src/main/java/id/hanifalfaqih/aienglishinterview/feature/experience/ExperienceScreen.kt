package id.hanifalfaqih.aienglishinterview.feature.experience

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import id.hanifalfaqih.aienglishinterview.ui.theme.AIEnglishInterviewTheme

/**
 * Minimal functional My Experience form: one entry, then Continue creates
 * the profile and the conversation and navigates with the real id.
 */
@Composable
fun ExperienceScreen(
    onInterviewReady: (conversationId: String) -> Unit,
    onReviewReady: () -> Unit,
    onGoPremium: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ExperienceViewModel = viewModel(),
) {
    val form = viewModel.form
    val uiState = viewModel.uiState
    val context = LocalContext.current

    if (uiState is ExperienceUiState.Done) {
        LaunchedEffect(uiState.conversationId) {
            onInterviewReady(uiState.conversationId)
        }
    }
    if (uiState is ExperienceUiState.Parsed) {
        LaunchedEffect(uiState.count) {
            onReviewReady()
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult // cancellation: stay put
        val result = readPickedFile(context.contentResolver, uri, MAX_RESUME_BYTES)
        val bytes = result.getOrNull()
        if (bytes == null) {
            viewModel.onUnreadableFile()
        } else {
            viewModel.parseResume(bytes, displayName(context.contentResolver, uri))
        }
    }

    val parsing = uiState is ExperienceUiState.Parsing
    val busy = uiState is ExperienceUiState.Submitting || parsing
    val canSubmit = !form.titleError && !form.descriptionError && !busy

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "My Experience", style = MaterialTheme.typography.headlineMedium)
        Text(
            text = "Tell us about one real experience to ground your interview.",
            style = MaterialTheme.typography.bodyMedium,
        )
        TextButton(onClick = onGoPremium) {
            Text("Premium practice")
        }
        OutlinedButton(
            onClick = { picker.launch(arrayOf("application/pdf")) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Upload resume (PDF)")
        }

        if (parsing) {
            Text(
                text = (uiState as ExperienceUiState.Parsing).step,
                style = MaterialTheme.typography.bodySmall,
            )
            CircularProgressIndicator()
        }

        OutlinedTextField(
            value = form.title,
            onValueChange = { viewModel.updateForm(form.copy(title = it)) },
            label = { Text("Title *") },
            supportingText = { if (form.title.isBlank()) Text("Title is required") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
            singleLine = true,
        )
        OutlinedTextField(
            value = form.organization,
            onValueChange = { viewModel.updateForm(form.copy(organization = it)) },
            label = { Text("Organization") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
            singleLine = true,
        )
        OutlinedTextField(
            value = form.role,
            onValueChange = { viewModel.updateForm(form.copy(role = it)) },
            label = { Text("Role") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
            singleLine = true,
        )
        OutlinedTextField(
            value = form.description,
            onValueChange = { viewModel.updateForm(form.copy(description = it)) },
            label = { Text("Description *") },
            supportingText = { if (form.description.isBlank()) Text("Description is required") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
            minLines = 3,
        )
        OutlinedTextField(
            value = form.skillsRaw,
            onValueChange = { viewModel.updateForm(form.copy(skillsRaw = it)) },
            label = { Text("Skills (comma separated)") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
            singleLine = true,
        )

        if (uiState is ExperienceUiState.Submitting) {
            Text(text = uiState.step, style = MaterialTheme.typography.bodySmall)
            CircularProgressIndicator()
        }
        if (uiState is ExperienceUiState.Error) {
            Text(
                text = uiState.message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = { viewModel.submit() }, enabled = canSubmit) {
                Text("Retry")
            }
        }

        Button(
            onClick = { viewModel.submit() },
            enabled = canSubmit,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Continue")
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ExperienceScreenPreview() {
    AIEnglishInterviewTheme {
        ExperienceScreen(onInterviewReady = {}, onReviewReady = {}, onGoPremium = {})
    }
}
