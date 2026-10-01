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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import id.hanifalfaqih.aienglishinterview.data.model.ExperienceItem
import id.hanifalfaqih.aienglishinterview.ui.components.Hairline
import id.hanifalfaqih.aienglishinterview.ui.components.QuietAction
import id.hanifalfaqih.aienglishinterview.ui.components.ScreenSubtitle
import id.hanifalfaqih.aienglishinterview.ui.components.ScreenTitle
import id.hanifalfaqih.aienglishinterview.ui.theme.AIEnglishInterviewTheme
import id.hanifalfaqih.aienglishinterview.ui.theme.Muted
import id.hanifalfaqih.aienglishinterview.ui.theme.PrimaryBlue

/**
 * Slice 2 resume review: shows the extracted candidates and lets the user
 * pick EXACTLY ONE as the interview context. Nothing is auto-selected and
 * nothing is sent to the backend here — selection hands off to the shared
 * confirmation screen.
 *
 * Presentation: a hairline-separated list; the whole row is the choice,
 * so no per-row button competes for attention.
 */
@Composable
fun SelectExperienceScreen(
    onSelected: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = ReviewDraft.items

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 24.dp),
    ) {
        Spacer(modifier = Modifier.height(40.dp))
        ScreenTitle("Review Your Experiences")
        Spacer(modifier = Modifier.height(8.dp))
        ScreenSubtitle("Pick exactly one experience to practice with.")
        Spacer(modifier = Modifier.height(20.dp))

        if (items.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Nothing to review",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = "Go back and import a resume first.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                itemsIndexed(items) { index, item ->
                    ExperienceRow(
                        item = item,
                        onSelect = {
                            SelectedExperience.item = item
                            SelectedExperience.typeLabel = null
                            onSelected()
                        },
                    )
                    Hairline()
                }
            }
        }

        QuietAction(text = "Back", onClick = onBack)
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun ExperienceRow(
    item: ExperienceItem,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = item.title,
            style = MaterialTheme.typography.titleMedium,
            color = PrimaryBlue,
        )
        val subtitle = listOfNotNull(item.organization, item.role).joinToString(" · ")
        if (subtitle.isNotEmpty()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )
        }
        if (item.description.isNotBlank()) {
            Text(
                text = item.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
        if (item.skills.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(text = "Skills:", style = MaterialTheme.typography.labelMedium, color = Muted)
                Text(
                    text = item.skills.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SelectExperienceScreenPreview() {
    val previous = ReviewDraft.items
    ReviewDraft.items = listOf(
        ExperienceItem(
            title = "Campus Cart",
            organization = "Tech Club",
            role = "Android dev",
            description = "Built a cart feature for the campus store app.",
            skills = listOf("Kotlin"),
        ),
    )
    AIEnglishInterviewTheme {
        SelectExperienceScreen(onSelected = {}, onBack = {})
    }
    ReviewDraft.items = previous
}
