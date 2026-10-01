package id.hanifalfaqih.aienglishinterview.feature.experience

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import id.hanifalfaqih.aienglishinterview.ui.components.BannerTone
import id.hanifalfaqih.aienglishinterview.ui.components.PrimaryButton
import id.hanifalfaqih.aienglishinterview.ui.components.QuietAction
import id.hanifalfaqih.aienglishinterview.ui.components.ScreenSubtitle
import id.hanifalfaqih.aienglishinterview.ui.components.ScreenTitle
import id.hanifalfaqih.aienglishinterview.ui.components.StatusBanner
import id.hanifalfaqih.aienglishinterview.ui.theme.AIEnglishInterviewTheme

/**
 * Slice 2 resume entry: pick a PDF, parse via the real backend, then
 * hand off to select-one review. Cancellation stays put; every failure is
 * an explicit error state, never faked content.
 *
 * The authoritative /resume/parse contract accepts PDF only — the picker
 * reflects that and never offers DOCX.
 *
 * Presentation: one quiet instruction strip, one primary action, flat
 * status banners — no oversized upload circles or nested panels.
 */
@Composable
fun ImportResumeScreen(
    onParsed: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ImportResumeViewModel = viewModel(),
) {
    val uiState = viewModel.uiState
    val context = LocalContext.current

    if (uiState is ImportUiState.Parsed) {
        LaunchedEffect(uiState.count) {
            onParsed()
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult // cancellation: stay put
        val result = readPickedFile(context.contentResolver, uri)
        val bytes = result.getOrNull()
        if (bytes == null) {
            viewModel.onUnreadableFile()
        } else {
            viewModel.parse(bytes, displayName(context.contentResolver, uri))
        }
    }

    val parsing = uiState is ImportUiState.Parsing

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
    ) {
        Spacer(modifier = Modifier.height(40.dp))
        ScreenTitle("Import from Resume")
        Spacer(modifier = Modifier.height(8.dp))
        ScreenSubtitle(
            "Choose a PDF. We'll extract candidate experiences for you to " +
                "review — nothing is sent to the interview until you pick one.",
        )

        Spacer(modifier = Modifier.height(28.dp))

        StatusBanner(
            text = "Supported format: PDF",
            tone = BannerTone.Info,
        )

        Spacer(modifier = Modifier.height(20.dp))

        PrimaryButton(
            text = "Choose file",
            onClick = { picker.launch(arrayOf(MIME_PDF)) },
            enabled = !parsing,
            busy = parsing,
            modifier = Modifier.fillMaxWidth(),
        )

        if (parsing) {
            Spacer(modifier = Modifier.height(16.dp))
            StatusBanner(
                text = "Parsing your resume — ${uiState.step}",
                tone = BannerTone.Progress,
                spinning = true,
            )
        }

        if (uiState is ImportUiState.Error) {
            Spacer(modifier = Modifier.height(16.dp))
            StatusBanner(
                text = uiState.message,
                tone = BannerTone.Error,
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
        QuietAction(text = "Back", onClick = onBack, enabled = !parsing)
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Preview(showBackground = true)
@Composable
private fun ImportResumeScreenPreview() {
    AIEnglishInterviewTheme {
        ImportResumeScreen(onParsed = {}, onBack = {})
    }
}
