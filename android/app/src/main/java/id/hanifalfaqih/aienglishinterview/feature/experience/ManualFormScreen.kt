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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import id.hanifalfaqih.aienglishinterview.ui.theme.AIEnglishInterviewTheme

/**
 * Slice 2 manual entry, step 2: the experience itself. Required: name and
 * what-you-did. The interviewer uncovers deeper context later, so no
 * full STAR answer is requested here.
 */
@Composable
fun ManualFormScreen(
    onContinue: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ManualFormViewModel = viewModel(),
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Tell us about it",
            style = MaterialTheme.typography.headlineMedium,
        )
        OutlinedTextField(
            value = viewModel.name,
            onValueChange = { viewModel.name = it },
            label = { Text("Experience name *") },
            supportingText = { if (viewModel.name.isBlank()) Text("Name is required") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = viewModel.role,
            onValueChange = { viewModel.role = it },
            label = { Text("Your role") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = viewModel.did,
            onValueChange = { viewModel.did = it },
            label = { Text("What did you do? *") },
            supportingText = { if (viewModel.did.isBlank()) Text("A short summary is required") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
        )
        OutlinedTextField(
            value = viewModel.challenge,
            onValueChange = { viewModel.challenge = it },
            label = { Text("What was challenging?") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
        )
        OutlinedTextField(
            value = viewModel.result,
            onValueChange = { viewModel.result = it },
            label = { Text("What was the result?") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onBack) {
                Text("Back")
            }
            Button(
                onClick = {
                    viewModel.saveToDraft()
                    onContinue()
                },
                enabled = viewModel.canContinue,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Review")
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ManualFormScreenPreview() {
    AIEnglishInterviewTheme {
        ManualFormScreen(onContinue = {}, onBack = {})
    }
}
