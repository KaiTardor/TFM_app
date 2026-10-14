package com.example.besharp.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.example.besharp.MainActivity
import com.example.besharp.R
import com.example.besharp.domain.model.FinalResolution

/**
 * Helper para crear y lanzar notificaciones locales cuando la IA termina de procesar
 * una foto mientras el usuario está fuera de la app.
 */
object NotificationHelper {
    @Volatile
    var isAppInForeground: Boolean = false

    private const val CHANNEL_ID = "besharp_ai_analysis"
    private const val CHANNEL_NAME = "Análisis de IA"
    private const val CHANNEL_DESCRIPTION = "Notificaciones cuando la IA termina de analizar una foto"
    private const val PREFERENCES_NAME = "besharp_notifications"
    private const val RESULTS_NOTIFICATIONS_KEY = "results_notifications_enabled"
    private const val SUCCESS_NOTIFICATIONS_KEY = "successful_results_notifications_enabled"
    private const val FAILED_NOTIFICATIONS_KEY = "failed_results_notifications_enabled"

    fun initializeProcessLifecycle() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                isAppInForeground = true
            }

            override fun onStop(owner: LifecycleOwner) {
                isAppInForeground = false
            }
        })
    }

    fun areResultNotificationsEnabled(context: Context): Boolean =
        notificationPreferences(context).getBoolean(RESULTS_NOTIFICATIONS_KEY, false)

    fun setResultNotificationsEnabled(context: Context, enabled: Boolean) {
        notificationPreferences(context).edit()
            .putBoolean(RESULTS_NOTIFICATIONS_KEY, enabled)
            .apply()
    }

    fun areSuccessfulResultNotificationsEnabled(context: Context): Boolean =
        notificationPreferences(context).getBoolean(SUCCESS_NOTIFICATIONS_KEY, true)

    fun setSuccessfulResultNotificationsEnabled(context: Context, enabled: Boolean) {
        notificationPreferences(context).edit().putBoolean(SUCCESS_NOTIFICATIONS_KEY, enabled).apply()
    }

    fun areFailedResultNotificationsEnabled(context: Context): Boolean =
        notificationPreferences(context).getBoolean(FAILED_NOTIFICATIONS_KEY, true)

    fun setFailedResultNotificationsEnabled(context: Context, enabled: Boolean) {
        notificationPreferences(context).edit().putBoolean(FAILED_NOTIFICATIONS_KEY, enabled).apply()
    }

    fun canPostNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return false
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return manager.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    /**
     * Crea el canal de notificaciones (necesario en Android 8+).
     * Es seguro llamar varias veces: si el canal ya existe, no hace nada.
     */
    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = CHANNEL_DESCRIPTION
            }

            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun showAssessmentNotification(
        context: Context,
        assessmentId: Long,
        imageName: String,
        resolution: FinalResolution,
        hasExecutionErrors: Boolean
    ) {
        val isSuccess = !hasExecutionErrors
        if (!shouldPostResultNotification(
                preferenceEnabled = areResultNotificationsEnabled(context),
                isAppInForeground = isAppInForeground,
                resultTypeEnabled = if (isSuccess) {
                    areSuccessfulResultNotificationsEnabled(context)
                } else {
                    areFailedResultNotificationsEnabled(context)
                }
            ) || !canPostNotifications(context)
        ) return

        createNotificationChannel(context.applicationContext)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_ANALYSIS, true)
            putExtra(EXTRA_ASSESSMENT_ID, assessmentId)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId(assessmentId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = if (isSuccess) "Evaluacion completada" else "Evaluacion con errores o cancelaciones"
        val text = when (resolution) {
            FinalResolution.AI_GENERATED_OR_EDITED ->
                "$imageName: se detecto procedencia de generacion o edicion con IA"
            FinalResolution.POSSIBLY_SYNTHETIC ->
                "$imageName: los analizadores encontraron indicios sinteticos"
            FinalResolution.VERIFIED_CAPTURE ->
                "$imageName: origen de captura verificado"
            FinalResolution.NO_SUFFICIENT_EVIDENCE ->
                "$imageName: no hay indicios suficientes; no demuestra autenticidad"
            FinalResolution.INCONCLUSIVE ->
                "$imageName: no fue posible alcanzar una conclusion"
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentTitle("BeSharp")
                    .setContentText("Una evaluacion ha terminado")
                    .build()
            )
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(notificationId(assessmentId), notification)
        } catch (_: SecurityException) {
            // El usuario no ha concedido el permiso de notificaciones.
        }
    }

    const val EXTRA_OPEN_ANALYSIS = "extra_open_analysis"
    const val EXTRA_ASSESSMENT_ID = "extra_assessment_id"

    private fun notificationPreferences(context: Context) = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private fun notificationId(assessmentId: Long): Int =
        (assessmentId xor (assessmentId ushr 32)).toInt()
}

internal fun shouldPostResultNotification(
    preferenceEnabled: Boolean,
    isAppInForeground: Boolean,
    resultTypeEnabled: Boolean = true
): Boolean = preferenceEnabled && resultTypeEnabled && !isAppInForeground
