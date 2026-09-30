package id.hanifalfaqih.aienglishinterview.feature.welcome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import id.hanifalfaqih.aienglishinterview.ui.theme.AIEnglishInterviewTheme

/**
 * Slice 1 entry: what the app is for, reassurance that non-internship
 * experience counts, and a single Get Started CTA. No auth, no pricing,
 * no extra onboarding steps.
 */
@Composable
fun WelcomeScreen(
    onGetStarted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Practice explaining what you've done.",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = "Prepare for your English internship interview through realistic voice conversations.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = "No internship experience? That's okay. Projects, competitions, organizations, and other real experiences count too.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(
            onClick = onGetStarted,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = "Get Started")
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun WelcomeScreenPreview() {
    AIEnglishInterviewTheme {
        WelcomeScreen(onGetStarted = {})
    }
}
