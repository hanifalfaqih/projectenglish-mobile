package id.hanifalfaqih.aienglishinterview.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import id.hanifalfaqih.aienglishinterview.ui.theme.Error
import id.hanifalfaqih.aienglishinterview.ui.theme.Hairline
import id.hanifalfaqih.aienglishinterview.ui.theme.InputBorder
import id.hanifalfaqih.aienglishinterview.ui.theme.Muted
import id.hanifalfaqih.aienglishinterview.ui.theme.Neutral200
import id.hanifalfaqih.aienglishinterview.ui.theme.PrimaryBlue
import id.hanifalfaqih.aienglishinterview.ui.theme.SecondaryTeal
import id.hanifalfaqih.aienglishinterview.ui.theme.Success

/**
 * Shared presentation kit for the Project English visual system: neutral
 * surfaces, hairline separation, blue as accent, one primary action.
 * Presentation only — no behavior, state, or navigation lives here.
 */

@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    )
}

@Composable
fun ScreenSubtitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = Muted,
        modifier = modifier,
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = Muted,
        modifier = modifier,
    )
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier,
        thickness = 1.dp,
        color = Hairline,
    )
}

/** Labelled metadata pair used across experience detail views. */
@Composable
fun MetaField(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = Muted)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

enum class BannerTone { Info, Progress, Success, Error }

/**
 * Flat status strip — the system's single way to show loading, notice,
 * success and error state. Never nested inside cards.
 */
@Composable
fun StatusBanner(
    text: String,
    tone: BannerTone,
    modifier: Modifier = Modifier,
    spinning: Boolean = false,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val (container, content) = when (tone) {
        BannerTone.Info -> PrimaryBlue.copy(alpha = 0.06f) to PrimaryBlue
        BannerTone.Progress -> SecondaryTeal.copy(alpha = 0.08f) to SecondaryTeal
        BannerTone.Success -> Success.copy(alpha = 0.08f) to Success
        BannerTone.Error -> Error.copy(alpha = 0.07f) to Error
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(container)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (spinning) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = content,
                strokeWidth = 2.dp,
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = content,
            modifier = Modifier.weight(1f),
        )
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) {
                Text(actionLabel, color = content, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** The one filled primary action on a screen. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !busy,
        modifier = modifier.height(52.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = PrimaryBlue,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        shape = RoundedCornerShape(12.dp),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp),
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = MaterialTheme.colorScheme.onPrimary,
                strokeWidth = 2.dp,
            )
            Spacer(modifier = Modifier.size(10.dp))
        }
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}

/** Quiet secondary action: outlined neutral, never competing with primary. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(52.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}

/** Plain text action, weakest level in the button hierarchy. */
@Composable
fun QuietAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    TextButton(onClick = onClick, enabled = enabled, modifier = modifier) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = PrimaryBlue)
    }
}

/**
 * Bring-focused-input-above-the-keyboard support lives in AppTextField:
 * focusing any field (top, middle, bottom; new focus while the keyboard
 * is already open; a keyboard opening under an already-focused field)
 * scrolls the whole labelled field into view via BringIntoViewRequester.
 *
 * Shared outline input: consistent border, radius, focus and error colors
 * across every form in the product. Fields sit on the canvas — no card
 * wrapper — with the label above. Host screens pair this with a scrollable
 * container and `imePadding()` so the viewport shrinks above the IME and
 * the scroll has room to bring focused fields fully into view.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    required: Boolean = false,
    error: String? = null,
    helperText: String? = null,
    enabled: Boolean = true,
    singleLine: Boolean = false,
    minLines: Int = 1,
) {
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val bottomPx = with(LocalDensity.current) { 24.dp.toPx() }
    var isFocused by remember { mutableStateOf(false) }
    var fieldSize by remember { mutableStateOf(IntSize.Zero) }
    // Re-run bringIntoView once the IME actually opens: the first request
    // can arrive while the viewport still has pre-keyboard bounds.
    val imeVisible = WindowInsets.isImeVisible

    LaunchedEffect(isFocused, imeVisible, fieldSize) {
        if (isFocused && fieldSize != IntSize.Zero) {
            bringIntoViewRequester.bringIntoView(
                Rect(
                    0f,
                    0f,
                    fieldSize.width.toFloat(),
                    fieldSize.height.toFloat() + bottomPx,
                ),
            )
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bringIntoViewRequester)
            .onSizeChanged { fieldSize = it },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = if (required) "$label *" else label,
            style = MaterialTheme.typography.labelMedium,
            color = Muted,
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusEvent { event ->
                    // Covers tap-to-focus and focus moves between fields
                    // while the keyboard is already open.
                    scope.launch { isFocused = event.isFocused }
                },
            enabled = enabled,
            singleLine = singleLine,
            minLines = minLines,
            isError = error != null,
            shape = RoundedCornerShape(12.dp),
            keyboardOptions = KeyboardOptions.Default,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = PrimaryBlue,
                unfocusedBorderColor = InputBorder,
                errorBorderColor = Error,
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                cursorColor = PrimaryBlue,
                errorCursorColor = Error,
            ),
        )
        when {
            error != null -> Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = Error,
            )
            helperText != null -> Text(
                text = helperText,
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )
        }
    }
}

/** Centered onboarding-style message used in empty/placeholder blocks. */
@Composable
fun EmptyState(text: String, supporting: String? = null, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        if (supporting != null) {
            Text(
                text = supporting,
                style = MaterialTheme.typography.bodyMedium,
                color = Muted,
                textAlign = TextAlign.Center,
            )
        }
    }
}
