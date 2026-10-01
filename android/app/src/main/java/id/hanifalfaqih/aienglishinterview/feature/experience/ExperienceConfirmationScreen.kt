package id.hanifalfaqih.aienglishinterview.feature.experience

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import id.hanifalfaqih.aienglishinterview.data.model.ExperienceItem
import id.hanifalfaqih.aienglishinterview.ui.components.BannerTone
import id.hanifalfaqih.aienglishinterview.ui.components.EmptyState
import id.hanifalfaqih.aienglishinterview.ui.components.Hairline
import id.hanifalfaqih.aienglishinterview.ui.components.MetaField
import id.hanifalfaqih.aienglishinterview.ui.components.PrimaryButton
import id.hanifalfaqih.aienglishinterview.ui.components.ScreenSubtitle
import id.hanifalfaqih.aienglishinterview.ui.components.ScreenTitle
import id.hanifalfaqih.aienglishinterview.ui.components.SecondaryButton
import id.hanifalfaqih.aienglishinterview.ui.components.StatusBanner
import id.hanifalfaqih.aienglishinterview.ui.theme.AIEnglishInterviewTheme
import id.hanifalfaqih.aienglishinterview.ui.theme.PrimaryBlue

/**
 * Slice 2 review gate for both entry paths: shows the ONE selected
 * experience, Edit returns to the form, Practice This hands off to the
 * existing interview entry path with the real conversation id.
 *
 * Presentation: an editorial summary — title, tracked metadata fields
 * separated by hairlines, no nesting — so confirming feels deliberate.
 */
@Composable
fun ExperienceConfirmationScreen(
    onInterviewReady: (conversationId: String) -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ConfirmationViewModel = viewModel(),
) {
    val uiState = viewModel.uiState
    val item = SelectedExperience.item
    val typeLabel = SelectedExperience.typeLabel

    if (uiState is ConfirmationUiState.Done) {
        LaunchedEffect(uiState.conversationId) {
            onInterviewReady(uiState.conversationId)
        }
    }

    val practicing = uiState is ConfirmationUiState.Practicing

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
    ) {
        Spacer(modifier = Modifier.height(40.dp))
        ScreenTitle("Review Your Experience")
        Spacer(modifier = Modifier.height(8.dp))
        ScreenSubtitle("Make sure everything looks good before we start the interview")

        if (item == null) {
            Spacer(modifier = Modifier.height(32.dp))
            EmptyState(
                text = "No experience selected",
                supporting = "Go back and choose an experience to practice with",
            )
            SecondaryButton(text = "Back", onClick = onEdit, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(24.dp))
            return@Column
        }

        Spacer(modifier = Modifier.height(28.dp))

        // Title + type — the meaningful context of the interview.
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (typeLabel != null) {
                Text(
                    text = typeLabel.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = PrimaryBlue,
                )
            }
            Text(
                text = item.title,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        Spacer(modifier = Modifier.height(20.dp))
        Hairline()

        MetaField(
            label = "What you did",
            value = item.description,
            modifier = Modifier.padding(vertical = 14.dp),
        )
        Hairline()

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(32.dp),
        ) {
            item.organization?.let { MetaField(label = "Organization", value = it, modifier = Modifier.weight(1f)) }
            item.role?.let { MetaField(label = "Role", value = it, modifier = Modifier.weight(1f)) }
        }
        if (item.organization != null || item.role != null) Hairline()

        if (item.skills.isNotEmpty()) {
            MetaField(
                label = "Skills",
                value = item.skills.joinToString(", "),
                modifier = Modifier.padding(vertical = 14.dp),
            )
            Hairline()
        }

        if (practicing) {
            Spacer(modifier = Modifier.height(16.dp))
            StatusBanner(
                text = "Setting up your interview — ${(uiState as ConfirmationUiState.Practicing).step}",
                tone = BannerTone.Progress,
                spinning = true,
            )
        }
        if (uiState is ConfirmationUiState.Error) {
            Spacer(modifier = Modifier.height(16.dp))
            StatusBanner(
                text = uiState.message,
                tone = BannerTone.Error,
                actionLabel = "Retry",
                onAction = { viewModel.practice() },
            )
        }

        Spacer(modifier = Modifier.weight(1f))
        Spacer(modifier = Modifier.height(24.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SecondaryButton(
                text = "Edit",
                onClick = onEdit,
                enabled = !practicing,
                modifier = Modifier.weight(1f),
            )
            PrimaryButton(
                text = "Practice This",
                onClick = { viewModel.practice() },
                modifier = Modifier.weight(2f),
                busy = practicing,
            )
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Preview(showBackground = true)
@Composable
private fun ExperienceConfirmationScreenPreview() {
    val previous = SelectedExperience.item
    SelectedExperience.item = ExperienceItem(
        title = "Campus Cart",
        organization = "Tech Club",
        role = "Android dev",
        description = "Built a cart feature.\n\nChallenge: flaky stock API.\n\nResult: demo day finalist.",
        skills = listOf("Kotlin"),
    )
    SelectedExperience.typeLabel = "Project"
    AIEnglishInterviewTheme {
        ExperienceConfirmationScreen(onInterviewReady = {}, onEdit = {})
    }
    SelectedExperience.item = previous
    SelectedExperience.typeLabel = null
}
