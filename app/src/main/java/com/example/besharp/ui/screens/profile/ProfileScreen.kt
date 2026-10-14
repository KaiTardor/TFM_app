package com.example.besharp.ui.screens.profile

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.SettingsApplications
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.besharp.R
import com.example.besharp.data.preferences.AppearancePreferences
import com.example.besharp.notifications.NotificationHelper
import com.example.besharp.ui.theme.appBackgroundBrush
import com.example.besharp.ui.common.readPhotoAccess

enum class SettingsPage {
    MENU,
    PROVIDERS,
    PERSONALIZATION,
    NOTIFICATIONS,
    PERMISSIONS,
    ABOUT,
    ANALYSIS_INFO
}

internal enum class PermissionAccessState {
    GRANTED,
    PARTIAL,
    DENIED
}

@Composable
fun SettingsHostScreen(
    innerPadding: PaddingValues,
    viewModel: ProfileViewModel,
    page: SettingsPage,
    onPageChanged: (SettingsPage) -> Unit,
    onOpenLegal: () -> Unit,
) {
    val context = LocalContext.current
    var notificationsEnabled by remember {
        mutableStateOf(NotificationHelper.areResultNotificationsEnabled(context))
    }
    var showNotificationSettings by rememberSaveable { mutableStateOf(false) }
    var successfulNotificationsEnabled by remember {
        mutableStateOf(NotificationHelper.areSuccessfulResultNotificationsEnabled(context))
    }
    var failedNotificationsEnabled by remember {
        mutableStateOf(NotificationHelper.areFailedResultNotificationsEnabled(context))
    }
    var themeMode by remember { mutableStateOf(AppearancePreferences.themeMode(context)) }
    var highLegibilityFont by remember {
        mutableStateOf(AppearancePreferences.highLegibilityFont(context))
    }
    var photoPermissionState by remember { mutableStateOf(readPhotoPermissionState(context)) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        NotificationHelper.setResultNotificationsEnabled(context, granted)
        notificationsEnabled = granted
        showNotificationSettings = !granted && (context as? Activity)?.let {
            !ActivityCompat.shouldShowRequestPermissionRationale(
                it,
                Manifest.permission.POST_NOTIFICATIONS
            )
        } == true
        if (granted) NotificationHelper.createNotificationChannel(context.applicationContext)
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        showNotificationSettings = false
        if (!enabled) {
            NotificationHelper.setResultNotificationsEnabled(context, false)
            notificationsEnabled = false
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            NotificationHelper.setResultNotificationsEnabled(context, true)
            NotificationHelper.createNotificationChannel(context.applicationContext)
            notificationsEnabled = true
        } else {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun openNotificationSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else {
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null)
            )
        }
        context.startActivity(intent)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val storedEnabled = NotificationHelper.areResultNotificationsEnabled(context)
                val permissionGranted = NotificationHelper.canPostNotifications(context)
                if (storedEnabled && !permissionGranted) {
                    NotificationHelper.setResultNotificationsEnabled(context, false)
                    showNotificationSettings = true
                }
                notificationsEnabled = storedEnabled && permissionGranted
                successfulNotificationsEnabled =
                    NotificationHelper.areSuccessfulResultNotificationsEnabled(context)
                failedNotificationsEnabled =
                    NotificationHelper.areFailedResultNotificationsEnabled(context)
                photoPermissionState = readPhotoPermissionState(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    BackHandler(enabled = page != SettingsPage.MENU) {
        onPageChanged(
            if (page == SettingsPage.ANALYSIS_INFO) SettingsPage.ABOUT
            else SettingsPage.MENU
        )
    }
    when (page) {
        SettingsPage.MENU -> SettingsMenu(
            innerPadding = innerPadding,
            onOpenProviders = { onPageChanged(SettingsPage.PROVIDERS) },
            onOpenPersonalization = { onPageChanged(SettingsPage.PERSONALIZATION) },
            onOpenNotifications = { onPageChanged(SettingsPage.NOTIFICATIONS) },
            onOpenPermissions = { onPageChanged(SettingsPage.PERMISSIONS) },
            onOpenAbout = { onPageChanged(SettingsPage.ABOUT) }
        )

        SettingsPage.PROVIDERS -> ProviderCredentialsScreen(
            innerPadding = innerPadding,
            viewModel = viewModel,
            onBack = { onPageChanged(SettingsPage.MENU) }
        )

        SettingsPage.PERSONALIZATION -> PersonalizationScreen(
            innerPadding = innerPadding,
            themeMode = themeMode,
            highLegibilityFont = highLegibilityFont,
            onThemeModeChange = {
                AppearancePreferences.setThemeMode(context, it)
                themeMode = it
            },
            onHighLegibilityFontChange = {
                AppearancePreferences.setHighLegibilityFont(context, it)
                highLegibilityFont = it
            },
            onBack = { onPageChanged(SettingsPage.MENU) }
        )

        SettingsPage.NOTIFICATIONS -> NotificationSettingsScreen(
            innerPadding = innerPadding,
            enabled = notificationsEnabled,
            successfulEnabled = successfulNotificationsEnabled,
            failedEnabled = failedNotificationsEnabled,
            showSettingsAction = showNotificationSettings,
            onEnabledChange = ::setNotificationsEnabled,
            onSuccessfulEnabledChange = {
                NotificationHelper.setSuccessfulResultNotificationsEnabled(context, it)
                successfulNotificationsEnabled = it
            },
            onFailedEnabledChange = {
                NotificationHelper.setFailedResultNotificationsEnabled(context, it)
                failedNotificationsEnabled = it
            },
            onOpenSystemSettings = ::openNotificationSettings,
            onSettingsNoticeExpired = { showNotificationSettings = false },
            onBack = { onPageChanged(SettingsPage.MENU) }
        )

        SettingsPage.PERMISSIONS -> AppPermissionsScreen(
            innerPadding = innerPadding,
            photoAccess = photoPermissionState,
            notificationAccess = if (NotificationHelper.canPostNotifications(context)) {
                PermissionAccessState.GRANTED
            } else {
                PermissionAccessState.DENIED
            },
            onOpenSystemSettings = {
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null)
                    )
                )
            },
            onBack = { onPageChanged(SettingsPage.MENU) }
        )

        SettingsPage.ABOUT -> AboutScreen(
            innerPadding = innerPadding,
            onOpenLegal = onOpenLegal,
            onOpenAnalysisInfo = { onPageChanged(SettingsPage.ANALYSIS_INFO) },
            onBack = { onPageChanged(SettingsPage.MENU) }
        )

        SettingsPage.ANALYSIS_INFO -> AnalysisInfoScreen(
            innerPadding = innerPadding,
            onBack = { onPageChanged(SettingsPage.ABOUT) }
        )
    }
}

private fun readPhotoPermissionState(context: android.content.Context): PermissionAccessState {
    val access = readPhotoAccess(context)
    return when {
        access.full -> PermissionAccessState.GRANTED
        access.partial -> PermissionAccessState.PARTIAL
        else -> PermissionAccessState.DENIED
    }
}

@Composable
private fun SettingsMenu(
    innerPadding: PaddingValues,
    onOpenProviders: () -> Unit,
    onOpenPersonalization: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenAbout: () -> Unit
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
        SettingsMenuItem(
            title = stringResource(R.string.settings_providers),
            icon = { Icon(Icons.Default.Key, contentDescription = null) },
            onClick = onOpenProviders
        )
        SettingsMenuItem(
            title = stringResource(R.string.settings_personalization),
            icon = { Icon(Icons.Default.Palette, contentDescription = null) },
            onClick = onOpenPersonalization
        )
        SettingsMenuItem(
            title = stringResource(R.string.settings_notifications),
            icon = { Icon(Icons.Default.Notifications, contentDescription = null) },
            onClick = onOpenNotifications
        )
        SettingsMenuItem(
            title = stringResource(R.string.settings_app_permissions),
            icon = { Icon(Icons.Default.SettingsApplications, contentDescription = null) },
            onClick = onOpenPermissions
        )
        SettingsMenuItem(
            title = stringResource(R.string.settings_about),
            icon = { Icon(Icons.Default.Info, contentDescription = null) },
            onClick = onOpenAbout
        )
    }
}

@Composable
internal fun SettingsMenuItem(
    title: String,
    description: String? = null,
    icon: @Composable () -> Unit,
    onClick: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RectangleShape,
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.onSurface),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            icon()
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 14.dp)
            ) {
                Text(title, fontWeight = FontWeight.Bold)
                description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null
            )
        }
    }
}
