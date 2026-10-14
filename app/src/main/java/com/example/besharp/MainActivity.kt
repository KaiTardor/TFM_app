package com.example.besharp

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.besharp.data.preferences.AppThemeMode
import com.example.besharp.data.preferences.AppearancePreferences
import com.example.besharp.notifications.NotificationHelper
import com.example.besharp.ui.BeSharpApp
import com.example.besharp.ui.theme.BeSharpTheme

class MainActivity : ComponentActivity() {
    private var openAnalysis by mutableStateOf(false)
    private var openAssessmentId by mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openAnalysis = savedInstanceState?.getBoolean(STATE_OPEN_ANALYSIS) == true
        openAssessmentId = savedInstanceState?.getLong(STATE_ASSESSMENT_ID, -1L)?.takeIf { it > 0 }
        handleIntent(intent)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        setContent {
            val context = applicationContext
            var themeMode by remember { mutableStateOf(AppearancePreferences.themeMode(context)) }
            var highLegibilityFont by remember {
                mutableStateOf(AppearancePreferences.highLegibilityFont(context))
            }
            val preferences = remember {
                context.getSharedPreferences(AppearancePreferences.PREFERENCES_NAME, MODE_PRIVATE)
            }
            DisposableEffect(preferences) {
                val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                    when (key) {
                        AppearancePreferences.KEY_THEME_MODE ->
                            themeMode = AppearancePreferences.themeMode(context)
                        AppearancePreferences.KEY_HIGH_LEGIBILITY_FONT ->
                            highLegibilityFont = AppearancePreferences.highLegibilityFont(context)
                    }
                }
                preferences.registerOnSharedPreferenceChangeListener(listener)
                onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
            }
            val darkTheme = when (themeMode) {
                AppThemeMode.SYSTEM -> isSystemInDarkTheme()
                AppThemeMode.LIGHT -> false
                AppThemeMode.DARK -> true
            }
            BeSharpTheme(
                darkTheme = darkTheme,
                highLegibilityFont = highLegibilityFont
            ) {
                BeSharpApp(
                    initialOpenAnalysis = openAnalysis,
                    initialAssessmentId = openAssessmentId,
                    onAnalysisOpened = {
                        openAnalysis = false
                        openAssessmentId = null
                        intent.removeExtra(NotificationHelper.EXTRA_OPEN_ANALYSIS)
                        intent.removeExtra(NotificationHelper.EXTRA_ASSESSMENT_ID)
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_OPEN_ANALYSIS, openAnalysis)
        openAssessmentId?.let { outState.putLong(STATE_ASSESSMENT_ID, it) }
    }

    private fun handleIntent(intent: Intent) {
        if (intent.getBooleanExtra(NotificationHelper.EXTRA_OPEN_ANALYSIS, false)) {
            intent.getLongExtra(NotificationHelper.EXTRA_ASSESSMENT_ID, -1L)
                .takeIf { it > 0 }
                ?.let { openAssessmentId = it }
            openAnalysis = true
        }
    }

    companion object {
        private const val STATE_OPEN_ANALYSIS = "state_open_analysis"
        private const val STATE_ASSESSMENT_ID = "state_assessment_id"
    }
}
