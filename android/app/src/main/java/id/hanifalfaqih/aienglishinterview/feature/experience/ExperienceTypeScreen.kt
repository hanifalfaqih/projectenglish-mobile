package id.hanifalfaqih.aienglishinterview.feature.experience

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import id.hanifalfaqih.aienglishinterview.ui.components.Hairline
import id.hanifalfaqih.aienglishinterview.ui.components.PrimaryButton
import id.hanifalfaqih.aienglishinterview.ui.components.QuietAction
import id.hanifalfaqih.aienglishinterview.ui.components.ScreenSubtitle
import id.hanifalfaqih.aienglishinterview.ui.components.ScreenTitle
import id.hanifalfaqih.aienglishinterview.ui.theme.AIEnglishInterviewTheme
import id.hanifalfaqih.aienglishinterview.ui.theme.Muted
import id.hanifalfaqih.aienglishinterview.ui.theme.OnAccentFill
import id.hanifalfaqih.aienglishinterview.ui.theme.PrimaryBlue

/**
 * Slice 2 manual entry, step 1: exactly one experience kind. The choice is
 * metadata; the interview scenario stays "Internship Interview".
 *
 * Presentation: a hairline-separated choice list with a radio indicator —
 * selected state uses blue, everything else stays neutral.
 */
@Composable
fun ExperienceTypeScreen(
    onContinue: (ExperienceType) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selected by remember { mutableStateOf(ManualDraft.type) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
    ) {
        Spacer(modifier = Modifier.height(40.dp))
        ScreenTitle("What kind of experience is this?")
        Spacer(modifier = Modifier.height(8.dp))
        ScreenSubtitle("Pick the closest match. Any real experience counts.")
        Spacer(modifier = Modifier.height(28.dp))

        ExperienceType.entries.forEach { type ->
            TypeRow(
                type = type,
                selected = selected == type,
                onClick = { selected = type },
            )
            Hairline()
        }

        Spacer(modifier = Modifier.height(24.dp))

        PrimaryButton(
            text = "Continue",
            onClick = { selected?.let(onContinue) },
            enabled = selected != null,
            modifier = Modifier.fillMaxWidth(),
        )
        QuietAction(text = "Back", onClick = onBack, modifier = Modifier.align(Alignment.CenterHorizontally))
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun TypeRow(
    type: ExperienceType,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = type.label,
                style = MaterialTheme.typography.titleMedium,
                color = if (selected) PrimaryBlue else MaterialTheme.colorScheme.onSurface,
            )
        }
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .border(
                    width = if (selected) 0.dp else 1.5.dp,
                    color = if (selected) PrimaryBlue else Muted,
                    shape = CircleShape,
                )
                .background(if (selected) PrimaryBlue else androidx.compose.ui.graphics.Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(id.hanifalfaqih.aienglishinterview.ui.theme.OnAccentFill),
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ExperienceTypeScreenPreview() {
    AIEnglishInterviewTheme {
        ExperienceTypeScreen(onContinue = {}, onBack = {})
    }
}
