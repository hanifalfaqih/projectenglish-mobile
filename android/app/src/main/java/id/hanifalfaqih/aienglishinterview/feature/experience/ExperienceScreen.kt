 package id.hanifalfaqih.aienglishinterview.feature.experience

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import id.hanifalfaqih.aienglishinterview.ui.components.AppTextField
import id.hanifalfaqih.aienglishinterview.ui.components.BannerTone
import id.hanifalfaqih.aienglishinterview.ui.components.PrimaryButton
import id.hanifalfaqih.aienglishinterview.ui.components.ScreenSubtitle
import id.hanifalfaqih.aienglishinterview.ui.components.ScreenTitle
import id.hanifalfaqih.aienglishinterview.ui.components.StatusBanner
import id.hanifalfaqih.aienglishinterview.ui.theme.AIEnglishInterviewTheme

/**
 * Minimal functional My Experience form: one entry, then Continue creates
 * the profile and the conversation and navigates with the real id.
 *
 * Presentation: fields sit directly on the neutral canvas with a clear
 * top-to-bottom reading order; status uses flat banners; one blue primary.
 */
@Composable
fun ExperienceScreen(
    onInterviewReady: (conversationId: String) -> Unit,
    onReviewReady: () -> Unit,
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
            .background(MaterialTheme.colorScheme.background)
            // Shrink the scroll viewport above the IME so bringIntoView
            // can always align focused fields clear of the keyboard.
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
    ) {
        Spacer(modifier = Modifier.height(40.dp))
        ScreenTitle("My Experience")
        Spacer(modifier = Modifier.height(8.dp))
        ScreenSubtitle("Tell us about one real experience to ground your interview")
        Spacer(modifier = Modifier.height(20.dp))

        // Resume import — quiet path, described in prose, not a panel.
        StatusBanner(
            text = "Have a resume? Import a PDF and pick from what's extracted.",
            tone = BannerTone.Info,
            actionLabel = "Upload PDF",
            onAction = { picker.launch(arrayOf("application/pdf")) },
        )

        if (parsing) {
            Spacer(modifier = Modifier.height(12.dp))
            StatusBanner(
                text = "Parsing your resume — ${(uiState as ExperienceUiState.Parsing).step}",
                tone = BannerTone.Progress,
                spinning = true,
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            AppTextField(
                value = form.title,
                onValueChange = { viewModel.updateForm(form.copy(title = it)) },
                label = "Title",
                required = true,
                error = if (form.titleError) "Title is required" else null,
                enabled = !busy,
                singleLine = true,
            )
            AppTextField(
                value = form.organization,
                onValueChange = { viewModel.updateForm(form.copy(organization = it)) },
                label = "Organization",
                enabled = !busy,
                singleLine = true,
            )
            AppTextField(
                value = form.role,
                onValueChange = { viewModel.updateForm(form.copy(role = it)) },
                label = "Role",
                enabled = !busy,
                singleLine = true,
            )
            AppTextField(
                value = form.description,
                onValueChange = { viewModel.updateForm(form.copy(description = it)) },
                label = "Description",
                required = true,
                error = if (form.descriptionError) "Description is required" else null,
                enabled = !busy,
                minLines = 3,
            )
            AppTextField(
                value = form.skillsRaw,
                onValueChange = { viewModel.updateForm(form.copy(skillsRaw = it)) },
                label = "Skills",
                enabled = !busy,
                singleLine = true,
                helperText = "Comma separated",
            )
        }

        if (uiState is ExperienceUiState.Submitting) {
            Spacer(modifier = Modifier.height(16.dp))
            StatusBanner(
                text = "Setting up your interview — ${uiState.step}",
                tone = BannerTone.Progress,
                spinning = true,
            )
        }
        if (uiState is ExperienceUiState.Error) {
            Spacer(modifier = Modifier.height(16.dp))
            StatusBanner(
                text = uiState.message,
                tone = BannerTone.Error,
                actionLabel = "Retry",
                onAction = { viewModel.submit() },
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        PrimaryButton(
            text = "Continue",
            onClick = { viewModel.submit() },
            enabled = canSubmit,
            busy = uiState is ExperienceUiState.Submitting,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Preview(showBackground = true)
@Composable
private fun ExperienceScreenPreview() {
    AIEnglishInterviewTheme {
        ExperienceScreen(onInterviewReady = {}, onReviewReady = {})
    }
}
