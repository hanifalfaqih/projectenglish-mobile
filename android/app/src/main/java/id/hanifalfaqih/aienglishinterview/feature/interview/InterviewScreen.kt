package id.hanifalfaqih.aienglishinterview.feature.interview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import id.hanifalfaqih.aienglishinterview.ui.theme.AIEnglishInterviewTheme

/**
 * Placeholder for the voice interview destination.
 * Real interview logic and voice are out of scope for this skeleton.
 */
@Composable
fun InterviewScreen(
    conversationId: String,
    onCompleteInterview: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Interview",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = "Placeholder destination: interview",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = "conversationId: $conversationId",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(top = 24.dp),
        ) {
            OutlinedButton(onClick = onBack) {
                Text("Back")
            }
            Button(onClick = onCompleteInterview) {
                Text("Complete Interview")
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun InterviewScreenPreview() {
    AIEnglishInterviewTheme {
        InterviewScreen(
            conversationId = "demo-conversation",
            onCompleteInterview = {},
            onBack = {},
        )
    }
}
