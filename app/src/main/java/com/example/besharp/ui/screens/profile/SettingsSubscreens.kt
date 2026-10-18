package com.example.besharp.ui.screens.profile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.besharp.data.preferences.AppThemeMode
import com.example.besharp.R
import com.example.besharp.ui.components.SettingsSubpageHeader
import com.example.besharp.ui.components.TimedNotice
import com.example.besharp.ui.theme.appBackgroundBrush

@Composable
internal fun PersonalizationScreen(
    innerPadding: PaddingValues,
    themeMode: AppThemeMode,
    highLegibilityFont: Boolean,
    onThemeModeChange: (AppThemeMode) -> Unit,
    onHighLegibilityFontChange: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.appBackgroundBrush)
            .padding(innerPadding)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SettingsSubpageHeader(stringResource(R.string.settings_personalization), onBack)
        Text(
            stringResource(R.string.settings_theme),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        AppThemeMode.entries.forEach { mode ->
            PreferenceChoiceRow(
                title = when (mode) {
                    AppThemeMode.SYSTEM -> stringResource(R.string.settings_theme_system)
                    AppThemeMode.LIGHT -> stringResource(R.string.settings_theme_light)
                    AppThemeMode.DARK -> stringResource(R.string.settings_theme_dark)
                },
                selected = themeMode == mode,
                onClick = { onThemeModeChange(mode) }
            )
        }

        Text(
            stringResource(R.string.settings_accessibility),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        SettingsToggleRow(
            title = stringResource(R.string.settings_high_legibility_font),
            checked = highLegibilityFont,
            onCheckedChange = onHighLegibilityFontChange
        )
    }
}

@Composable
private fun PreferenceChoiceRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RectangleShape,
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.onSurface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            RadioButton(selected = selected, onClick = null)
        }
    }
}

@Composable
internal fun NotificationSettingsScreen(
    innerPadding: PaddingValues,
    enabled: Boolean,
    successfulEnabled: Boolean,
    failedEnabled: Boolean,
    showSettingsAction: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onSuccessfulEnabledChange: (Boolean) -> Unit,
    onFailedEnabledChange: (Boolean) -> Unit,
    onOpenSystemSettings: () -> Unit,
    onSettingsNoticeExpired: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.appBackgroundBrush)
            .padding(innerPadding)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SettingsSubpageHeader(stringResource(R.string.settings_notifications), onBack)
        SettingsToggleRow(
            title = stringResource(R.string.settings_result_notifications),
            checked = enabled,
            onCheckedChange = onEnabledChange
        )
        SettingsToggleRow(
            title = stringResource(R.string.settings_completed_notifications),
            checked = successfulEnabled,
            enabled = enabled,
            onCheckedChange = onSuccessfulEnabledChange
        )
        SettingsToggleRow(
            title = stringResource(R.string.settings_failed_notifications),
            checked = failedEnabled,
            enabled = enabled,
            onCheckedChange = onFailedEnabledChange
        )
        if (showSettingsAction) {
            TimedNotice(
                message = stringResource(R.string.settings_notification_permission_disabled),
                actionLabel = stringResource(R.string.settings_action),
                onAction = onOpenSystemSettings,
                onExpired = onSettingsNoticeExpired
            )
        }
        OutlinedButton(onClick = onOpenSystemSettings, shape = RectangleShape) {
            Text(stringResource(R.string.settings_open_notification_settings))
        }
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RectangleShape,
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.onSurface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = checked,
                    enabled = enabled,
                    role = Role.Switch,
                    onValueChange = onCheckedChange
                )
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Switch(checked = checked, enabled = enabled, onCheckedChange = null)
        }
    }
}

@Composable
internal fun AppPermissionsScreen(
    innerPadding: PaddingValues,
    photoAccess: PermissionAccessState,
    notificationAccess: PermissionAccessState,
    onOpenSystemSettings: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.appBackgroundBrush)
            .padding(innerPadding)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SettingsSubpageHeader(stringResource(R.string.settings_app_permissions), onBack)
        PermissionStatusRow(
            title = stringResource(R.string.settings_permission_photos),
            state = photoAccess
        )
        PermissionStatusRow(
            title = stringResource(R.string.settings_permission_notifications),
            state = notificationAccess
        )
        OutlinedButton(onClick = onOpenSystemSettings, shape = RectangleShape) {
            Text(stringResource(R.string.settings_open_app_settings))
        }
    }
}

@Composable
private fun PermissionStatusRow(title: String, state: PermissionAccessState) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RectangleShape,
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.onSurface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(
                text = stringResource(
                    when (state) {
                        PermissionAccessState.GRANTED -> R.string.settings_permission_granted
                        PermissionAccessState.PARTIAL -> R.string.settings_permission_partial
                        PermissionAccessState.DENIED -> R.string.settings_permission_denied
                    }
                ),
                color = if (state == PermissionAccessState.DENIED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.secondary
                }
            )
        }
    }
}

@Composable
internal fun AboutScreen(
    innerPadding: PaddingValues,
    onOpenLegal: () -> Unit,
    onOpenAnalysisInfo: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.appBackgroundBrush)
            .padding(innerPadding)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SettingsSubpageHeader(stringResource(R.string.settings_about), onBack)
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RectangleShape,
            border = BorderStroke(2.dp, MaterialTheme.colorScheme.onSurface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    stringResource(R.string.app_name).uppercase(),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(stringResource(R.string.settings_version), style = MaterialTheme.typography.bodySmall)
            }
        }
        SettingsMenuItem(
            title = stringResource(R.string.settings_analysis_info),
            description = stringResource(R.string.settings_analysis_info_description),
            icon = { Icon(Icons.Default.Info, contentDescription = null) },
            onClick = onOpenAnalysisInfo
        )
        SettingsMenuItem(
            title = stringResource(R.string.privacy_title),
            description = stringResource(R.string.settings_privacy_description),
            icon = { Icon(Icons.Default.PrivacyTip, contentDescription = null) },
            onClick = onOpenLegal
        )
    }
}

@Composable
internal fun AnalysisInfoScreen(
    innerPadding: PaddingValues,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.appBackgroundBrush)
            .padding(innerPadding)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SettingsSubpageHeader(stringResource(R.string.settings_analysis_info), onBack)
        InfoSection(R.string.info_credentials_title, R.string.info_credentials_body)
        InfoSection(R.string.info_processing_title, R.string.info_processing_body)
        InfoSection(R.string.info_results_title, R.string.info_results_body)
    }
}

@Composable
private fun InfoSection(title: Int, body: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(title), fontWeight = FontWeight.Bold)
        Text(stringResource(body), style = MaterialTheme.typography.bodyMedium)
    }
}
