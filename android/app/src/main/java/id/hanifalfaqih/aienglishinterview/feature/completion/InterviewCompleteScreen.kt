package id.hanifalfaqih.aienglishinterview.feature.completion

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import id.hanifalfaqih.aienglishinterview.ui.theme.AIEnglishInterviewTheme

/**
 * Slice 5 completion gate: the backend closed the interview (authoritative).
 * The final interviewer audio has already finished (the Complete action is
 * only enabled after playback). From here the user views feedback or goes
 * back; the closed conversation is never resumed.
 */
@Composable
fun InterviewCompleteScreen(
    onViewFeedback: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Interview Complete",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = "Nice work. Here's some feedback to help you improve your professional English communication.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(
            onClick = onViewFeedback,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("View Feedback")
        }
        OutlinedButton(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Back")
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun InterviewCompleteScreenPreview() {
    AIEnglishInterviewTheme {
        InterviewCompleteScreen(onViewFeedback = {}, onBack = {})
    }
}
