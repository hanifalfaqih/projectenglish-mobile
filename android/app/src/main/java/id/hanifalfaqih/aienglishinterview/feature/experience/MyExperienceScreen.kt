package id.hanifalfaqih.aienglishinterview.feature.experience

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import id.hanifalfaqih.aienglishinterview.ui.theme.AIEnglishInterviewTheme

/**
 * Slice 1 baseline: header, supporting copy, and the two first-class
 * entry points. Both cards carry equal visual weight; their actions are
 * intentionally unwired — resume import and manual entry land in Slice 2.
 * No fake parsing, no fake experience data.
 */
@Composable
fun MyExperienceScreen(
    onImportResume: () -> Unit,
    onAddManually: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "My Experience",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = "Choose something you've actually worked on. It doesn't have to be an internship.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Card(
            onClick = onImportResume,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "Import from Resume",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "Upload a PDF or DOCX and we'll help find your experiences.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Card(
            onClick = onAddManually,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "Add Experience Manually",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "Tell us about a project, internship, competition, or organization.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun MyExperienceScreenPreview() {
    AIEnglishInterviewTheme {
        MyExperienceScreen(onImportResume = {}, onAddManually = {})
    }
}
