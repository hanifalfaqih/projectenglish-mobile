package id.hanifalfaqih.aienglishinterview.feature.experience

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import id.hanifalfaqih.aienglishinterview.ui.components.AppTextField
import id.hanifalfaqih.aienglishinterview.ui.components.PrimaryButton
import id.hanifalfaqih.aienglishinterview.ui.components.QuietAction
import id.hanifalfaqih.aienglishinterview.ui.components.ScreenSubtitle
import id.hanifalfaqih.aienglishinterview.ui.components.ScreenTitle
import id.hanifalfaqih.aienglishinterview.ui.components.SecondaryButton
import id.hanifalfaqih.aienglishinterview.ui.theme.AIEnglishInterviewTheme
import id.hanifalfaqih.aienglishinterview.ui.theme.Muted

/**
 * Slice 2 manual entry, step 2: the experience itself. Required: name and
 * what-you-did. The interviewer uncovers deeper context later, so no
 * full STAR answer is requested here.
 *
 * Presentation: fields on the canvas with labels above, grouped required
 * first then optional detail, one primary action.
 */
@Composable
fun ManualFormScreen(
    onContinue: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ManualFormViewModel = viewModel(),
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // Shrink the scroll viewport above the IME so bringIntoView
            // can always align focused fields clear of the keyboard.
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
    ) {
        Spacer(modifier = Modifier.height(40.dp))
        ScreenTitle("Tell us about it")
        Spacer(modifier = Modifier.height(8.dp))
        ScreenSubtitle(
            "Describe your experience. The interviewer will explore deeper " +
                "context later.",
        )

        Spacer(modifier = Modifier.height(28.dp))

        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            // Experience name (required)
            AppTextField(
                value = viewModel.name,
                onValueChange = { viewModel.name = it },
                label = "Experience name",
                required = true,
                error = if (viewModel.name.isBlank()) "Name is required" else null,
                singleLine = true,
            )

            // Role (optional)
            AppTextField(
                value = viewModel.role,
                onValueChange = { viewModel.role = it },
                label = "Your role",
                singleLine = true,
            )

            // What did you do (required)
            AppTextField(
                value = viewModel.did,
                onValueChange = { viewModel.did = it },
                label = "What did you do?",
                required = true,
                error = if (viewModel.did.isBlank()) "A short summary is required" else null,
                minLines = 3,
            )

            // What was challenging (optional)
            AppTextField(
                value = viewModel.challenge,
                onValueChange = { viewModel.challenge = it },
                label = "What was challenging?",
                minLines = 2,
            )

            // What was the result (optional)
            AppTextField(
                value = viewModel.result,
                onValueChange = { viewModel.result = it },
                label = "What was the result?",
                minLines = 2,
            )
        }

        Spacer(modifier = Modifier.height(28.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SecondaryButton(
                text = "Back",
                onClick = onBack,
                modifier = Modifier.weight(1f),
            )
            PrimaryButton(
                text = "Review",
                onClick = {
                    viewModel.saveToDraft()
                    onContinue()
                },
                enabled = viewModel.canContinue,
                modifier = Modifier.weight(2f),
            )
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Preview(showBackground = true)
@Composable
private fun ManualFormScreenPreview() {
    AIEnglishInterviewTheme {
        ManualFormScreen(onContinue = {}, onBack = {})
    }
}
