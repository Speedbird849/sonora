package dev.sonora.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The type scale every screen draws its text with.
 *
 * The weights and the negative tracking are most of what makes a list of covers and titles read as
 * designed rather than as a stock Material screen. Three things carry it:
 *
 *  - **Heavier headings.** Material's defaults top out at W400 for the large sizes, so a screen title
 *    sits at the same weight as the body text under it and the hierarchy comes only from size.
 *  - **Negative letter spacing, tightening as the type grows.** Display and headline text set at a
 *    negative tracking stops the words drifting apart at large sizes, which is what stops a big
 *    title from looking like a row of separate letters. Body text keeps the default, because
 *    tightening small text hurts more than it helps.
 *  - **W600 rather than W500 on the small styles.** A label at medium weight over a dark background
 *    is the difference between a caption and a caption you have to lean in to read.
 *
 * No line height is set on any style, so every one falls back to the platform's own metric for its
 * size. Setting them by hand is how a stack of rows ends up with a line box that does not match the
 * row height it was laid out against.
 *
 * The family is the platform's own rather than a bundled one. Only the weights the scale asks for
 * are declared, so the rest are synthesised from these rather than coming from a second typeface.
 */
private val Scale = Typography(
    displayLarge = TextStyle(
        fontWeight = FontWeight.W800,
        fontSize = 34.sp,
        letterSpacing = (-0.8).sp,
    ),
    headlineLarge = TextStyle(
        fontWeight = FontWeight.W800,
        fontSize = 30.sp,
        letterSpacing = (-0.7).sp,
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.W700,
        fontSize = 22.sp,
        letterSpacing = (-0.4).sp,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.W700,
        fontSize = 20.sp,
        letterSpacing = (-0.3).sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.W600,
        fontSize = 16.sp,
        letterSpacing = (-0.2).sp,
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.W400,
        fontSize = 16.sp,
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.W400,
        fontSize = 14.sp,
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.W400,
        fontSize = 12.sp,
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.W600,
        fontSize = 12.sp,
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.W600,
        fontSize = 11.sp,
    ),
)

/**
 * The same scale with every style on one family.
 *
 * The copy exists because [Typography] only fills the styles it is given, and a style left off keeps
 * the platform default — which on a device whose system font is not the same as the one the rest of
 * the scale is drawn in is a row of text in two typefaces.
 */
private fun Typography.withFamily(family: FontFamily): Typography = Typography(
    displayLarge = displayLarge.copy(fontFamily = family),
    displayMedium = displayMedium.copy(fontFamily = family),
    displaySmall = displaySmall.copy(fontFamily = family),
    headlineLarge = headlineLarge.copy(fontFamily = family),
    headlineMedium = headlineMedium.copy(fontFamily = family),
    headlineSmall = headlineSmall.copy(fontFamily = family),
    titleLarge = titleLarge.copy(fontFamily = family),
    titleMedium = titleMedium.copy(fontFamily = family),
    titleSmall = titleSmall.copy(fontFamily = family),
    bodyLarge = bodyLarge.copy(fontFamily = family),
    bodyMedium = bodyMedium.copy(fontFamily = family),
    bodySmall = bodySmall.copy(fontFamily = family),
    labelLarge = labelLarge.copy(fontFamily = family),
    labelMedium = labelMedium.copy(fontFamily = family),
    labelSmall = labelSmall.copy(fontFamily = family),
)

internal val SonoraTypography: Typography = Scale.withFamily(FontFamily.Default)
