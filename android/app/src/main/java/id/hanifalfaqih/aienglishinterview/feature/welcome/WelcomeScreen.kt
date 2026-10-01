package id.hanifalfaqih.aienglishinterview.feature.welcome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import id.hanifalfaqih.aienglishinterview.ui.components.PrimaryButton
import id.hanifalfaqih.aienglishinterview.ui.theme.AIEnglishInterviewTheme
import id.hanifalfaqih.aienglishinterview.ui.theme.Muted
import id.hanifalfaqih.aienglishinterview.ui.theme.PrimaryBlue
import id.hanifalfaqih.aienglishinterview.ui.theme.SecondaryTeal

/**
 * Slice 1 entry: what the app is for, reassurance that non-internship
 * experience counts, and a single Get Started CTA. No auth, no pricing,
 * no extra onboarding steps.
 *
 * Landing presentation: neutral canvas, editorial headline, a small brand
 * mark — blue lives in the wordmark and the CTA, not the background.
 */
@Composable
fun WelcomeScreen(
    onGetStarted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        Spacer(modifier = Modifier.height(72.dp))

        // Brand mark: three restrained strokes, the product "moment".
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BrandDash(color = PrimaryBlue, width = 28.dp)
            BrandDash(color = SecondaryTeal, width = 16.dp)
            BrandDash(color = Muted, width = 8.dp)
        }

        Spacer(modifier = Modifier.height(40.dp))

        Text(
            text = "Project English",
            style = MaterialTheme.typography.labelMedium,
            color = Muted,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Practice explaining\nwhat you've done.",
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Prepare for your English internship interview through realistic voice conversations.",
            style = MaterialTheme.typography.bodyLarge,
            color = Muted,
        )

        Spacer(modifier = Modifier.weight(1f))

        // Reassurance line — plain prose, not a decorated panel.
        Text(
            text = "No internship experience? That's okay. Projects, competitions, organizations, and other real experiences count too.",
            style = MaterialTheme.typography.bodyMedium,
            color = Muted,
            textAlign = TextAlign.Left,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(28.dp))

        PrimaryButton(
            text = "Get Started",
            onClick = onGetStarted,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun BrandDash(color: androidx.compose.ui.graphics.Color, width: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = Modifier
            .size(width, 6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(color)
    )
}

@Preview(showBackground = true)
@Composable
private fun WelcomeScreenPreview() {
    AIEnglishInterviewTheme {
        WelcomeScreen(onGetStarted = {})
    }
}
