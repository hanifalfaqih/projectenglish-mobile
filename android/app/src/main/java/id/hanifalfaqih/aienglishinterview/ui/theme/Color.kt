package id.hanifalfaqih.aienglishinterview.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

// ============================================================================
// Raw palette
// ============================================================================

// Neutral colors
val Neutral900 = Color(0xFF111827)
val Neutral800 = Color(0xFF1F2937)
val Neutral700 = Color(0xFF374151)
val Neutral600 = Color(0xFF4B5563)
val Neutral500 = Color(0xFF6B7280)
val Neutral400 = Color(0xFF9CA3AF)
val Neutral300 = Color(0xFFD1D5DB)
val Neutral200 = Color(0xFFE5E7EB)
val Neutral100 = Color(0xFFF3F4F6)
val Neutral50 = Color(0xFFF9FAFB)
val White = Color(0xFFFFFFFF)

// Fixed brand-adjacent colors (theme-independent where used)
val PrimaryBlueDark = Color(0xFF1D4ED8)
val PrimaryBlueLight = Color(0xFF3B82F6)
val SecondaryTealDark = Color(0xFF0D9488)
val SuccessDark = Color(0xFF059669)
val ErrorDark = Color(0xFFDC2626)
val Warning = Color(0xFFF59E0B)
val WarningDark = Color(0xFFD97706)
val Info = Color(0xFF3B82F6)

// Background colors (light-mode literals for the light scheme)
val BackgroundLight = Color(0xFFFAFAFA)
val SurfaceLight = Color(0xFFFFFFFF)
val SurfaceVariantLight = Color(0xFFF3F4F6)

// Raw brand role colors (light values). Dark equivalents live in
// DarkAppColors; the semantic tokens below resolve per theme.
private val PrimaryBlueRaw = Color(0xFF2563EB)
private val SecondaryTealRaw = Color(0xFF14B8A6)
private val SuccessRaw = Color(0xFF10B981)
private val ErrorRaw = Color(0xFFEF4444)

// ============================================================================
// Theme-resolving design tokens
// ============================================================================

/**
 * Design-system color roles used directly by the shared presentation kit
 * and screens beyond MaterialTheme's own slots: muted secondary text,
 * hairline separators, and brand accent roles (blue primary, teal
 * progress/voice, success, error) referenced as semantic tokens.
 *
 * Theme switching flows through [LocalAppColors], so dark mode renders the
 * SAME design system, not a second one: deep neutral canvas (never pure
 * black), near-white ink, muted light-gray secondary text, a cobalt blue
 * readable on dark surfaces (and safe under white button text), and lifted
 * teal/green/red roles with dark-appropriate contrast.
 */
data class AppColors(
    val muted: Color,
    val hairline: Color,
    val inputBorder: Color,
    val primary: Color,
    val teal: Color,
    val success: Color,
    val error: Color,
    val onPrimaryFill: Color,
)

val LightAppColors = AppColors(
    muted = Neutral600,
    hairline = Neutral200,
    inputBorder = Neutral200,
    primary = PrimaryBlueRaw,
    teal = SecondaryTealRaw,
    success = SuccessRaw,
    error = ErrorRaw,
    onPrimaryFill = White,
)

val DarkAppColors = AppColors(
    muted = Color(0xFF9AA4B2),
    hairline = Color(0xFF2A2F36),
    inputBorder = Color(0xFF3A424D),
    // Mid-cobalt: ~4.6:1 as text on the dark canvas; filled buttons use
    // dark navy ink (onPrimaryFill below), matching Material 3 guidance
    // that dark-mode primaries stay in the mid range.
    primary = Color(0xFF5C8FF5),
    teal = Color(0xFF2DD4BF),
    success = Color(0xFF34D399),
    // Dual-purpose error red: readable as text on the dark canvas and
    // under white fill text (Stop button / filled error controls).
    error = Color(0xFFEE5B5B),
    onPrimaryFill = Color(0xFF06182F),
)

val LocalAppColors = compositionLocalOf { LightAppColors }

// ---- Semantic tokens -------------------------------------------------------
// Call sites read these names exactly as before; each resolves through the
// current [LocalAppColors]. Composable scope only — the audit confirmed no
// call site uses these tokens in a non-composable position or as a default
// argument value.

/** Secondary/muted text. */
val Muted: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.muted

/** Subtle 1px separators. */
val Hairline: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.hairline

/** Unfocused text-field border. */
val InputBorder: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.inputBorder

/** Ink for content drawn on filled primary/teal/error buttons. */
val OnAccentFill: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.onPrimaryFill

/** Brand blue — primary actions, selection, emphasis, links. */
val PrimaryBlue: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.primary

/** Teal — in-progress / voice / completion moments. */
val SecondaryTeal: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.teal

/** Green success role. */
val Success: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.success

/** Red error role. */
val Error: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.error

// ============================================================================
// Material3 color schemes
// ============================================================================

// Light color scheme
val LightColorScheme = androidx.compose.material3.lightColorScheme(
    primary = PrimaryBlueRaw,
    onPrimary = White,
    primaryContainer = Color(0xFFDBEAFE),
    onPrimaryContainer = PrimaryBlueDark,

    secondary = SecondaryTealRaw,
    onSecondary = White,
    secondaryContainer = Color(0xFFCCFBF1),
    onSecondaryContainer = SecondaryTealDark,

    tertiary = Color(0xFF8B5CF6),
    onTertiary = White,
    tertiaryContainer = Color(0xFFEDE9FE),
    onTertiaryContainer = Color(0xFF6D28D9),

    error = ErrorRaw,
    onError = White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = ErrorDark,

    background = BackgroundLight,
    onBackground = Neutral900,

    surface = SurfaceLight,
    onSurface = Neutral900,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = Neutral600,

    outline = Neutral300,
    outlineVariant = Neutral200,

    inverseSurface = Neutral900,
    inverseOnSurface = White,
    inversePrimary = PrimaryBlueLight,
)

// Dark color scheme — brand-aligned deep neutrals; roles mirror
// DarkAppColors so Material widgets and the shared kit agree.
// Filled buttons carry deep navy ink on the mid-cobalt/teal fills —
// dark-appropriate contrast; light mode keeps white (unchanged design).
val DarkColorScheme = androidx.compose.material3.darkColorScheme(
    primary = DarkAppColors.primary,
    onPrimary = Color(0xFF06182F),
    primaryContainer = Color(0xFF1E3A6E),
    onPrimaryContainer = Color(0xFFD3E3FF),

    secondary = DarkAppColors.teal,
    onSecondary = Color(0xFF00201B),
    secondaryContainer = Color(0xFF0E4740),
    onSecondaryContainer = Color(0xFFBDF3E9),

    tertiary = Color(0xFF9DB8FF),
    onTertiary = Color(0xFF0B1B38),
    tertiaryContainer = Color(0xFF233353),
    onTertiaryContainer = Color(0xFFD9E4FF),

    error = DarkAppColors.error,
    onError = Color(0xFF320A0A),
    errorContainer = Color(0xFF5C1A1A),
    onErrorContainer = Color(0xFFFFDAD6),

    background = Color(0xFF121417),
    onBackground = Color(0xFFECEFF3),

    surface = Color(0xFF121417),
    onSurface = Color(0xFFECEFF3),
    surfaceVariant = Color(0xFF1F2429),
    onSurfaceVariant = Color(0xFF9AA4B2),

    outline = Color(0xFF4A525E),
    outlineVariant = DarkAppColors.hairline,

    inverseSurface = Color(0xFFECEFF3),
    inverseOnSurface = Color(0xFF1F2429),
    inversePrimary = Color(0xFF3B63C9),
)
