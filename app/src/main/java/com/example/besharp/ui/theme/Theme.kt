package com.example.besharp.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

// Esquinas rectas para la estética pixelada
val PixelShapes = Shapes(
    small = RoundedCornerShape(0.dp),
    medium = RoundedCornerShape(0.dp),
    large = RoundedCornerShape(0.dp)
)

private val LightColorScheme = lightColorScheme(
    primary = PrimaryBlue,
    onPrimary = OnPrimaryLight,
    primaryContainer = PrimaryContainerLight,
    onPrimaryContainer = OnPrimaryContainerLight,
    secondary = SecondaryBlue,
    onSecondary = OnSecondaryLight,
    background = BackgroundLight,
    onBackground = OnBackgroundLight,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight
)

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryBlueDark,
    onPrimary = OnPrimaryDark,
    primaryContainer = PrimaryContainerDark,
    onPrimaryContainer = OnPrimaryContainerDark,
    secondary = SecondaryBlueDark,
    onSecondary = OnSecondaryDark,
    background = BackgroundDark,
    onBackground = OnBackgroundDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark
)

data class VerdictColors(
    val authentic: Color,
    val synthetic: Color,
    val inconclusive: Color
)

data class AppChromeColors(
    val container: Color,
    val content: Color
)

private val LocalVerdictColors = staticCompositionLocalOf {
    VerdictColors(
        authentic = VerdictAuthenticLight,
        synthetic = VerdictSyntheticLight,
        inconclusive = VerdictInconclusiveLight
    )
}

private val LocalAppBackgroundColors = staticCompositionLocalOf {
    listOf(BackgroundGradientTopLight, BackgroundGradientMiddleLight, BackgroundLight)
}

private val LocalAppChromeColors = staticCompositionLocalOf {
    AppChromeColors(PrimaryBlue, OnPrimaryLight)
}

val MaterialTheme.verdictColors: VerdictColors
    @Composable get() = LocalVerdictColors.current

val MaterialTheme.appBackgroundBrush: Brush
    @Composable get() = Brush.verticalGradient(LocalAppBackgroundColors.current)

val MaterialTheme.appChromeColors: AppChromeColors
    @Composable get() = LocalAppChromeColors.current

@Composable
fun BeSharpTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    highLegibilityFont: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val verdictColors = if (darkTheme) {
        VerdictColors(VerdictAuthenticDark, VerdictSyntheticDark, VerdictInconclusiveDark)
    } else {
        VerdictColors(VerdictAuthenticLight, VerdictSyntheticLight, VerdictInconclusiveLight)
    }
    val backgroundColors = if (darkTheme) {
        listOf(BackgroundGradientTopDark, BackgroundGradientMiddleDark, BackgroundDark)
    } else {
        listOf(BackgroundGradientTopLight, BackgroundGradientMiddleLight, BackgroundLight)
    }
    val chromeColors = if (darkTheme) {
        AppChromeColors(PrimaryContainerDark, OnPrimaryContainerDark)
    } else {
        AppChromeColors(PrimaryBlue, OnPrimaryLight)
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(
        LocalVerdictColors provides verdictColors,
        LocalAppBackgroundColors provides backgroundColors,
        LocalAppChromeColors provides chromeColors
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = if (highLegibilityFont) HighLegibilityTypography else Typography,
            shapes = PixelShapes,
            content = content
        )
    }
}
