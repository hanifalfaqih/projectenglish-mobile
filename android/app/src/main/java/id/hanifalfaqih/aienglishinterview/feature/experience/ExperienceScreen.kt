package id.hanifalfaqih.aienglishinterview.feature.experience

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import id.hanifalfaqih.aienglishinterview.ui.theme.AIEnglishInterviewTheme

/**
 * Placeholder for the "My Experience" destination.
 * Real experience input and interview creation are out of scope for this skeleton.
 */
@Composable
fun ExperienceScreen(
    onStartInterview: () -> Unit,
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
            text = "My Experience",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = "Placeholder destination: experience",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        Button(
            onClick = onStartInterview,
            modifier = Modifier.padding(top = 24.dp),
        ) {
            Text("Start Interview")
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ExperienceScreenPreview() {
    AIEnglishInterviewTheme {
        ExperienceScreen(onStartInterview = {})
    }
}
