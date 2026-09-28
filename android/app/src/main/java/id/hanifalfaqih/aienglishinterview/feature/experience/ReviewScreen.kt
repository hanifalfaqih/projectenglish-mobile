package id.hanifalfaqih.aienglishinterview.feature.experience

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import id.hanifalfaqih.aienglishinterview.ui.theme.AIEnglishInterviewTheme

/**
 * Human verification of parsed resume experiences. Local edits only until
 * confirm, which creates the profile and conversation with real ids.
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
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "Review experiences", style = MaterialTheme.typography.headlineMedium)
        Text(
            text = "Check what was extracted from your resume before it becomes interview context.",
            style = MaterialTheme.typography.bodyMedium,
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(viewModel.forms) { index, form ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "Experience ${index + 1}",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        OutlinedTextField(
                            value = form.title,
                            onValueChange = { viewModel.update(index, form.copy(title = it)) },
                            label = { Text("Title *") },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !confirming,
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = form.organization,
                            onValueChange = { viewModel.update(index, form.copy(organization = it)) },
                            label = { Text("Organization") },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !confirming,
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = form.role,
                            onValueChange = { viewModel.update(index, form.copy(role = it)) },
                            label = { Text("Role") },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !confirming,
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = form.description,
                            onValueChange = { viewModel.update(index, form.copy(description = it)) },
                            label = { Text("Description *") },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !confirming,
                            minLines = 2,
                        )
                        OutlinedTextField(
                            value = form.skillsRaw,
                            onValueChange = { viewModel.update(index, form.copy(skillsRaw = it)) },
                            label = { Text("Skills (comma separated)") },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !confirming,
                            singleLine = true,
                        )
                        TextButton(
                            onClick = { viewModel.remove(index) },
                            enabled = !confirming,
                        ) {
                            Text("Remove")
                        }
                    }
                }
            }
            item {
                OutlinedButton(onClick = { viewModel.add() }, enabled = !confirming) {
                    Text("Add experience")
                }
            }
        }

        if (uiState is ReviewUiState.Confirming) {
            Text(text = uiState.step, style = MaterialTheme.typography.bodySmall)
            CircularProgressIndicator()
        }
        if (uiState is ReviewUiState.Error) {
            Text(
                text = uiState.message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onBack, enabled = !confirming) {
                Text("Back")
            }
            Button(
                onClick = { viewModel.confirm() },
                enabled = !confirming && viewModel.forms.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Confirm & continue")
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ReviewScreenPreview() {
    AIEnglishInterviewTheme {
        ReviewScreen(onInterviewReady = {}, onBack = {})
    }
}
