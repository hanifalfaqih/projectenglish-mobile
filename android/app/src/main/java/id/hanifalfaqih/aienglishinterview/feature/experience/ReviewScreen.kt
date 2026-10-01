package id.hanifalfaqih.aienglishinterview.feature.experience

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import id.hanifalfaqih.aienglishinterview.ui.components.AppTextField
import id.hanifalfaqih.aienglishinterview.ui.components.BannerTone
import id.hanifalfaqih.aienglishinterview.ui.components.Hairline
import id.hanifalfaqih.aienglishinterview.ui.components.PrimaryButton
import id.hanifalfaqih.aienglishinterview.ui.components.ScreenSubtitle
import id.hanifalfaqih.aienglishinterview.ui.components.ScreenTitle
import id.hanifalfaqih.aienglishinterview.ui.components.SecondaryButton
import id.hanifalfaqih.aienglishinterview.ui.components.StatusBanner
import id.hanifalfaqih.aienglishinterview.ui.theme.AIEnglishInterviewTheme
import id.hanifalfaqih.aienglishinterview.ui.theme.Error
import id.hanifalfaqih.aienglishinterview.ui.theme.Muted
import id.hanifalfaqih.aienglishinterview.ui.theme.PrimaryBlue

/**
 * Human verification of parsed resume experiences. Local edits only until
 * confirm, which creates the profile and conversation with real ids.
 *
 * Presentation: each extracted experience is a numbered editorial section
 * separated by hairlines — grouped content, not floating cards.
 */
@Composable
fun ReviewScreen(
    onInterviewReady: (conversationId: String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ReviewViewModel = viewModel(),
) {
    val uiState = viewModel.uiState

    if (uiState is ReviewUiState.Done) {
        LaunchedEffect(uiState.conversationId) {
            onInterviewReady(uiState.conversationId)
        }
    }

    val confirming = uiState is ReviewUiState.Confirming

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // Shrink the LazyColumn viewport above the IME so focused
            // fields inside it can always scroll clear of the keyboard.
            .imePadding()
            .padding(horizontal = 24.dp),
    ) {
        Spacer(modifier = Modifier.height(40.dp))
        ScreenTitle("Review experiences")
        Spacer(modifier = Modifier.height(8.dp))
        ScreenSubtitle(
            "Check what was extracted from your resume before it becomes " +
                "interview context.",
        )
        Spacer(modifier = Modifier.height(20.dp))

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            itemsIndexed(viewModel.forms) { index, form ->
                ExperienceReviewSection(
                    index = index,
                    form = form,
                    confirming = confirming,
                    onUpdate = { viewModel.update(index, it) },
                    onRemove = { viewModel.remove(index) },
                )
                Hairline()
            }
            item {
                TextButton(
                    onClick = { viewModel.add() },
                    enabled = !confirming,
                ) {
                    Text(
                        text = "+ Add experience",
                        style = MaterialTheme.typography.labelLarge,
                        color = PrimaryBlue,
                    )
                }
            }
        }

        if (uiState is ReviewUiState.Confirming) {
            StatusBanner(
                text = "Setting up your interview — ${uiState.step}",
                tone = BannerTone.Progress,
                spinning = true,
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
        if (uiState is ReviewUiState.Error) {
            StatusBanner(
                text = uiState.message,
                tone = BannerTone.Error,
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SecondaryButton(
                text = "Back",
                onClick = onBack,
                enabled = !confirming,
                modifier = Modifier.weight(1f),
            )
            PrimaryButton(
                text = "Confirm & continue",
                onClick = { viewModel.confirm() },
                enabled = viewModel.forms.isNotEmpty(),
                busy = confirming,
                modifier = Modifier.weight(2f),
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun ExperienceReviewSection(
    index: Int,
    form: ExperienceForm,
    confirming: Boolean,
    onUpdate: (ExperienceForm) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "EXPERIENCE ${index + 1}",
                style = MaterialTheme.typography.labelMedium,
                color = Muted,
            )
            TextButton(onClick = onRemove, enabled = !confirming) {
                Text("Remove", color = Error, style = MaterialTheme.typography.labelLarge)
            }
        }

        AppTextField(
            value = form.title,
            onValueChange = { onUpdate(form.copy(title = it)) },
            label = "Title",
            required = true,
            enabled = !confirming,
            singleLine = true,
        )
        AppTextField(
            value = form.organization,
            onValueChange = { onUpdate(form.copy(organization = it)) },
            label = "Organization",
            enabled = !confirming,
            singleLine = true,
        )
        AppTextField(
            value = form.role,
            onValueChange = { onUpdate(form.copy(role = it)) },
            label = "Role",
            enabled = !confirming,
            singleLine = true,
        )
        AppTextField(
            value = form.description,
            onValueChange = { onUpdate(form.copy(description = it)) },
            label = "Description",
            required = true,
            enabled = !confirming,
            minLines = 2,
        )
        AppTextField(
            value = form.skillsRaw,
            onValueChange = { onUpdate(form.copy(skillsRaw = it)) },
            label = "Skills",
            enabled = !confirming,
            singleLine = true,
            helperText = "Comma separated",
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ReviewScreenPreview() {
    AIEnglishInterviewTheme {
        ReviewScreen(onInterviewReady = {}, onBack = {})
    }
}
