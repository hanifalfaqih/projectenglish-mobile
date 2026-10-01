package id.hanifalfaqih.aienglishinterview.feature.experience

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import id.hanifalfaqih.aienglishinterview.R
import id.hanifalfaqih.aienglishinterview.ui.components.Hairline
import id.hanifalfaqih.aienglishinterview.ui.components.ScreenSubtitle
import id.hanifalfaqih.aienglishinterview.ui.components.ScreenTitle
import id.hanifalfaqih.aienglishinterview.ui.theme.AIEnglishInterviewTheme
import id.hanifalfaqih.aienglishinterview.ui.theme.Muted
import id.hanifalfaqih.aienglishinterview.ui.theme.PrimaryBlue

/**
 * Slice 1 baseline: header, supporting copy, and the two first-class
 * entry points. Both choices are offered equally; their actions are
 * intentionally unwired — resume import and manual entry land in Slice 2.
 * No fake parsing, no fake experience data.
 *
 * Presentation: two quiet list rows separated by hairlines — choices, not
 * decorated panels.
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
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 24.dp),
    ) {
        Spacer(modifier = Modifier.height(40.dp))
        ScreenTitle("My Experience")
        Spacer(modifier = Modifier.height(8.dp))
        ScreenSubtitle(
            "Choose something you've actually worked on. " +
                "It doesn't have to be an internship.",
        )
        Spacer(modifier = Modifier.height(32.dp))

        ChoiceRow(
            iconRes = R.drawable.ic_document,
            title = "Import from Resume",
            description = "Upload a PDF and we'll help find your experiences.",
            onClick = onImportResume,
        )
        Hairline()
        ChoiceRow(
            iconRes = R.drawable.ic_add,
            title = "Add Experience Manually",
            description = "Tell us about a project, internship, competition, or organization.",
            onClick = onAddManually,
        )
        Hairline()
    }
}

@Composable
private fun ChoiceRow(
    iconRes: Int,
    title: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 20.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = null,
            tint = PrimaryBlue,
            modifier = Modifier
                .size(22.dp)
                .align(Alignment.CenterVertically),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = Muted,
            )
        }
        Text(
            text = "›",
            style = MaterialTheme.typography.headlineSmall,
            color = Muted,
            modifier = Modifier.align(Alignment.CenterVertically),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun MyExperienceScreenPreview() {
    AIEnglishInterviewTheme {
        MyExperienceScreen(onImportResume = {}, onAddManually = {})
    }
}
