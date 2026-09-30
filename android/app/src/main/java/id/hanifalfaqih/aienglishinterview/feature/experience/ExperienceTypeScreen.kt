package id.hanifalfaqih.aienglishinterview.feature.experience

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import id.hanifalfaqih.aienglishinterview.ui.theme.AIEnglishInterviewTheme

/**
 * Slice 2 manual entry, step 1: exactly one experience kind. The choice is
 * metadata; the interview scenario stays "Internship Interview".
 */
@Composable
fun ExperienceTypeScreen(
    onContinue: (ExperienceType) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selected by remember { mutableStateOf(ManualDraft.type) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "What kind of experience is this?",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = "Pick the closest match. Any real experience counts.",
            style = MaterialTheme.typography.bodyMedium,
        )
        ExperienceType.entries.forEach { type ->
            Card(
                onClick = { selected = type },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RadioButton(
                        selected = selected == type,
                        onClick = { selected = type },
                    )
                    Text(
                        text = type.label,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onBack) {
                Text("Back")
            }
            Button(
                onClick = { selected?.let(onContinue) },
                enabled = selected != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Continue")
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ExperienceTypeScreenPreview() {
    AIEnglishInterviewTheme {
        ExperienceTypeScreen(onContinue = {}, onBack = {})
    }
}
