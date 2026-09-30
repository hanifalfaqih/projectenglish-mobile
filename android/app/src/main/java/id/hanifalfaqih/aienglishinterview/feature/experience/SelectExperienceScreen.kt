package id.hanifalfaqih.aienglishinterview.feature.experience

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import id.hanifalfaqih.aienglishinterview.data.model.ExperienceItem
import id.hanifalfaqih.aienglishinterview.ui.theme.AIEnglishInterviewTheme

/**
 * Slice 2 resume review: shows the extracted candidates and lets the user
 * pick EXACTLY ONE as the interview context. Nothing is auto-selected and
 * nothing is sent to the backend here — selection hands off to the shared
 * confirmation screen.
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
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Review Your Experiences",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = "Pick exactly one experience to practice with.",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (items.isEmpty()) {
            Text(
                text = "Nothing to review. Go back and import a resume first.",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(items) { _, item ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            val subtitle = listOfNotNull(item.organization, item.role)
                                .joinToString(" · ")
                            if (subtitle.isNotEmpty()) {
                                Text(
                                    text = subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (item.description.isNotBlank()) {
                                Text(
                                    text = item.description,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 3,
                                )
                            }
                            Button(
                                onClick = {
                                    SelectedExperience.item = item
                                    SelectedExperience.typeLabel = null
                                    onSelected()
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Select")
                            }
                        }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onBack) {
                Text("Back")
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
