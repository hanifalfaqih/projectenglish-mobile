package id.hanifalfaqih.aienglishinterview.feature.experience

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import id.hanifalfaqih.aienglishinterview.data.model.ExperienceItem
import id.hanifalfaqih.aienglishinterview.ui.theme.AIEnglishInterviewTheme

/**
 * Slice 2 review gate for both entry paths: shows the ONE selected
 * experience, Edit returns to the form, Practice This hands off to the
 * existing interview entry path with the real conversation id.
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
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Review Your Experience",
            style = MaterialTheme.typography.headlineMedium,
        )
        if (item == null) {
            Text(
                text = "No experience selected. Go back and choose one.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = onEdit) {
                Text("Back")
            }
            return@Column
        }
        if (typeLabel != null) {
            LabeledValue(label = "Type", value = typeLabel)
        }
        LabeledValue(label = "Experience", value = item.title)
        item.organization?.let { LabeledValue(label = "Organization", value = it) }
        item.role?.let { LabeledValue(label = "Role", value = it) }
        LabeledValue(label = "What you did", value = item.description)
        if (item.skills.isNotEmpty()) {
            LabeledValue(label = "Skills", value = item.skills.joinToString(", "))
        }

        if (practicing) {
            Text(text = uiState.step, style = MaterialTheme.typography.bodySmall)
            CircularProgressIndicator()
        }
        if (uiState is ConfirmationUiState.Error) {
            Text(
                text = uiState.message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = { viewModel.practice() }, enabled = !practicing) {
                Text("Retry")
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onEdit, enabled = !practicing) {
                Text("Edit")
            }
            Button(
                onClick = { viewModel.practice() },
                enabled = !practicing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Practice This")
            }
        }
    }
}

@Composable
private fun LabeledValue(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = label, style = MaterialTheme.typography.labelMedium)
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
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
