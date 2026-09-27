package id.hanifalfaqih.aienglishinterview.feature.feedback

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
 * Placeholder for the professional communication feedback destination.
 * Real feedback retrieval is out of scope for this skeleton.
 */
@Composable
fun FeedbackScreen(
    conversationId: String,
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
            text = "Feedback",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = "Placeholder destination: feedback",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = "conversationId: $conversationId",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
        OutlinedButton(
            onClick = onBack,
            modifier = Modifier.padding(top = 24.dp),
        ) {
            Text("Back")
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun FeedbackScreenPreview() {
    AIEnglishInterviewTheme {
        FeedbackScreen(
            conversationId = "demo-conversation",
            onBack = {},
        )
    }
}
