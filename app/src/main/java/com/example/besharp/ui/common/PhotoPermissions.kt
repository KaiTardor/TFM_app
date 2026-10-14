package com.example.besharp.ui.common

// Centraliza la lectura y solicitud de acceso completo o parcial a fotos.

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

internal data class PhotoAccess(val full: Boolean, val partial: Boolean) {
    val granted: Boolean get() = full || partial
}

internal fun photoPermissions(sdk: Int = Build.VERSION.SDK_INT): Array<String> = when {
    sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
    )
    sdk >= Build.VERSION_CODES.TIRAMISU -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

internal fun readPhotoAccess(context: Context): PhotoAccess {
    val permissions = photoPermissions()
    val full = ContextCompat.checkSelfPermission(context, permissions[0]) ==
        PackageManager.PERMISSION_GRANTED
    val partial = permissions.getOrNull(1)?.let {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    } == true
    return PhotoAccess(full, partial)
}

// La UI recibe callbacks; los detalles de Android quedan fuera de la navegacion.
@Composable
internal fun rememberPhotoAccessRequest(
    galleryVisible: Boolean,
    onAccessChanged: (Boolean, Boolean, Boolean) -> Unit,
    onSynchronize: (Boolean) -> Unit
): () -> Unit {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var requested by rememberSaveable { mutableStateOf(false) }
    val currentGalleryVisible by rememberUpdatedState(galleryVisible)
    val currentAccessChanged by rememberUpdatedState(onAccessChanged)
    val currentSynchronize by rememberUpdatedState(onSynchronize)

    fun canRequestAgain(): Boolean = (context as? Activity)?.let {
        ActivityCompat.shouldShowRequestPermissionRationale(it, photoPermissions()[0])
    } == true

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val access = readPhotoAccess(context)
        currentAccessChanged(access.full, access.partial, access.granted || canRequestAgain())
        if (access.granted) currentSynchronize(true)
    }
    val request: () -> Unit = {
        requested = true
        launcher.launch(photoPermissions())
    }

    LaunchedEffect(galleryVisible) {
        if (galleryVisible) {
            val access = readPhotoAccess(context)
            currentAccessChanged(access.full, access.partial, access.granted || canRequestAgain())
            if (access.granted) currentSynchronize(false)
            else if (!requested) request()
        }
    }

    // Al volver de Ajustes, el usuario puede haber cambiado los permisos.
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val access = readPhotoAccess(context)
                currentAccessChanged(access.full, access.partial, access.granted || canRequestAgain())
                if (access.granted && currentGalleryVisible) currentSynchronize(false)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return request
}
