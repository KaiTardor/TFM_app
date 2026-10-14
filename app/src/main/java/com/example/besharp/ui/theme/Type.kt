package com.example.besharp.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.besharp.R

// Definimos la familia de fuentes VT323 (pixelada)
val PixelFontFamily = FontFamily(
    Font(R.font.vt323, FontWeight.Normal)
)

// Reemplazamos la tipografía nativa por la pixelada ajustada para leerse bien
val Typography = Typography(
    displayLarge = TextStyle(
        fontFamily = PixelFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 48.sp,
        lineHeight = 52.sp
    ),
    displayMedium = TextStyle(
        fontFamily = PixelFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 42.sp,
        lineHeight = 46.sp
    ),
    displaySmall = TextStyle(
        fontFamily = PixelFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 36.sp,
        lineHeight = 40.sp
    ),
    headlineLarge = TextStyle(
        fontFamily = PixelFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 36.sp,
        lineHeight = 40.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = PixelFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 34.sp,
        lineHeight = 38.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = PixelFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 32.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.sp
    ),
    titleLarge = TextStyle(
        fontFamily = PixelFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 24.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontFamily = PixelFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 20.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp
    ),
    titleSmall = TextStyle(
        fontFamily = PixelFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 18.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.15.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = PixelFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 20.sp, // Aumentado ligeramente para fuentes pixeladas
        lineHeight = 26.sp,
        letterSpacing = 0.5.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = PixelFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.25.sp
    ),
    bodySmall = TextStyle(
        fontFamily = PixelFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.4.sp
    ),
    labelLarge = TextStyle(
        fontFamily = PixelFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 18.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.4.sp
    ),
    labelMedium = TextStyle(
        fontFamily = PixelFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.4.sp
    ),
    labelSmall = TextStyle(
        fontFamily = PixelFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    )
)

private fun TextStyle.highLegibility() = copy(
    fontFamily = FontFamily.SansSerif,
    letterSpacing = 0.sp
)

val HighLegibilityTypography = Typography.copy(
    displayLarge = Typography.displayLarge.highLegibility(),
    displayMedium = Typography.displayMedium.highLegibility(),
    displaySmall = Typography.displaySmall.highLegibility(),
    headlineLarge = Typography.headlineLarge.highLegibility(),
    headlineMedium = Typography.headlineMedium.highLegibility(),
    headlineSmall = Typography.headlineSmall.highLegibility(),
    titleLarge = Typography.titleLarge.highLegibility(),
    titleMedium = Typography.titleMedium.highLegibility(),
    titleSmall = Typography.titleSmall.highLegibility(),
    bodyLarge = Typography.bodyLarge.highLegibility(),
    bodyMedium = Typography.bodyMedium.highLegibility(),
    bodySmall = Typography.bodySmall.highLegibility(),
    labelLarge = Typography.labelLarge.highLegibility(),
    labelMedium = Typography.labelMedium.highLegibility(),
    labelSmall = Typography.labelSmall.highLegibility()
)
