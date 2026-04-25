package io.github.cloudburst.grayscaler.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

fun baseTypography() = Typography(
    displayLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = 0.sp
    ),
    displayMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 45.sp,
        lineHeight = 52.sp,
        letterSpacing = 0.sp
    ),
    displaySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 36.sp,
        lineHeight = 44.sp,
        letterSpacing = 0.sp
    ),
    headlineLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = 0.sp
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.sp
    ),
    headlineSmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 17.sp,
        lineHeight = 23.sp,
        letterSpacing = 0.1.sp
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.5.sp
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 13.5.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        letterSpacing = 0.4.sp
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        letterSpacing = 0.5.sp
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    )
)

fun themedTypography(
    headerFontFamily: FontFamily?,
    subheaderFontFamily: FontFamily?,
    mainFontFamily: FontFamily?,
    tertiaryFontFamily: FontFamily?,
): Typography {
    val base = baseTypography()
    return base.copy(
        displayLarge = headerFontFamily?.let { base.displayLarge.copy(fontFamily = it) } ?: base.displayLarge,
        displayMedium = headerFontFamily?.let { base.displayMedium.copy(fontFamily = it) } ?: base.displayMedium,
        displaySmall = headerFontFamily?.let { base.displaySmall.copy(fontFamily = it) } ?: base.displaySmall,
        headlineLarge = headerFontFamily?.let { base.headlineLarge.copy(fontFamily = it) } ?: base.headlineLarge,
        headlineMedium = headerFontFamily?.let { base.headlineMedium.copy(fontFamily = it) } ?: base.headlineMedium,
        headlineSmall = headerFontFamily?.let { base.headlineSmall.copy(fontFamily = it) } ?: base.headlineSmall,
        titleLarge = headerFontFamily?.let { base.titleLarge.copy(fontFamily = it) } ?: base.titleLarge,
        titleMedium = subheaderFontFamily?.let { base.titleMedium.copy(fontFamily = it) } ?: base.titleMedium,
        titleSmall = subheaderFontFamily?.let { base.titleSmall.copy(fontFamily = it) } ?: base.titleSmall,
        bodyLarge = mainFontFamily?.let { base.bodyLarge.copy(fontFamily = it) } ?: base.bodyLarge,
        bodyMedium = mainFontFamily?.let { base.bodyMedium.copy(fontFamily = it) } ?: base.bodyMedium,
        bodySmall = tertiaryFontFamily?.let { base.bodySmall.copy(fontFamily = it) } ?: base.bodySmall,
        labelLarge = tertiaryFontFamily?.let { base.labelLarge.copy(fontFamily = it) } ?: base.labelLarge,
        labelMedium = tertiaryFontFamily?.let { base.labelMedium.copy(fontFamily = it) } ?: base.labelMedium,
        labelSmall = tertiaryFontFamily?.let { base.labelSmall.copy(fontFamily = it) } ?: base.labelSmall,
    )
}

fun scaledTypography(typography: Typography, scaleFactor: Float): Typography =
    typography.copy(
        displayLarge = typography.displayLarge.scale(scaleFactor),
        displayMedium = typography.displayMedium.scale(scaleFactor),
        displaySmall = typography.displaySmall.scale(scaleFactor),
        headlineLarge = typography.headlineLarge.scale(scaleFactor),
        headlineMedium = typography.headlineMedium.scale(scaleFactor),
        headlineSmall = typography.headlineSmall.scale(scaleFactor),
        titleLarge = typography.titleLarge.scale(scaleFactor),
        titleMedium = typography.titleMedium.scale(scaleFactor),
        titleSmall = typography.titleSmall.scale(scaleFactor),
        bodyLarge = typography.bodyLarge.scale(scaleFactor),
        bodyMedium = typography.bodyMedium.scale(scaleFactor),
        bodySmall = typography.bodySmall.scale(scaleFactor),
        labelLarge = typography.labelLarge.scale(scaleFactor),
        labelMedium = typography.labelMedium.scale(scaleFactor),
        labelSmall = typography.labelSmall.scale(scaleFactor)
    )

private fun TextStyle.scale(scaleFactor: Float): TextStyle = copy(
    fontSize = fontSize * scaleFactor,
    lineHeight = lineHeight * scaleFactor,
    letterSpacing = letterSpacing * scaleFactor
)
