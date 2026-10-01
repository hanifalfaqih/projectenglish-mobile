package id.hanifalfaqih.aienglishinterview.feature.completion

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import id.hanifalfaqih.aienglishinterview.R
import id.hanifalfaqih.aienglishinterview.ui.components.PrimaryButton
import id.hanifalfaqih.aienglishinterview.ui.components.QuietAction
import id.hanifalfaqih.aienglishinterview.ui.theme.AIEnglishInterviewTheme
import id.hanifalfaqih.aienglishinterview.ui.theme.Muted
import id.hanifalfaqih.aienglishinterview.ui.theme.SecondaryTeal
import id.hanifalfaqih.aienglishinterview.ui.theme.OnAccentFill

/**
 * Slice 5 completion gate: the backend closed the interview (authoritative).
 * The final interviewer audio has already finished (the Complete action is
 * only enabled after playback). From here the user views feedback or goes
 * back; the closed conversation is never resumed.
 *
 * Presentation: a calm transition — small confirmation mark, one line of
 * prose, one clear action. No decorative "what's next" panels.
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
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.weight(1f))

        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(SecondaryTeal),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_check),
                contentDescription = null,
                tint = OnAccentFill,
                modifier = Modifier.size(30.dp),
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Interview complete",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = "Nice work. Your feedback is ready — it's specific to the experience you practiced.",
            style = MaterialTheme.typography.bodyLarge,
            color = Muted,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.weight(1f))

        PrimaryButton(
            text = "View Feedback",
            onClick = onViewFeedback,
            modifier = Modifier.fillMaxWidth(),
        )
        QuietAction(text = "Back", onClick = onBack)

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Preview(showBackground = true)
@Composable
private fun InterviewCompleteScreenPreview() {
    AIEnglishInterviewTheme {
        InterviewCompleteScreen(onViewFeedback = {}, onBack = {})
    }
}
