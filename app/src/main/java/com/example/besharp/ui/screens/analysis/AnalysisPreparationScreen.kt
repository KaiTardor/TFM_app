package com.example.besharp.ui.screens.analysis

import android.net.Uri
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.example.besharp.R
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import java.io.File
import com.example.besharp.data.local.createCapturedPhotoFile
import com.example.besharp.data.local.markCaptureReady
import com.example.besharp.data.local.cancelCapturedPhoto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.besharp.ui.theme.appBackgroundBrush
import com.example.besharp.ui.components.ExpandableSection

@Composable
fun AnalysisPreparationScreen(
    innerPadding: PaddingValues,
    viewModel: AnalysisPreparationViewModel,
    onCapturedPhoto: suspend (String, String) -> Unit,
    onOpenAppGallery: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onAnalysisSubmitted: () -> Unit,
    reopenConsent: Boolean = false,
    onConsentReopened: () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val pendingCaptures by viewModel.pendingCaptures.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingCameraUri by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingCameraName by rememberSaveable { mutableStateOf("") }
    var pendingCameraPath by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingCameraResult by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var cameraSessionId by rememberSaveable { mutableIntStateOf(0) }
    var cameraError by remember { mutableStateOf(false) }
    var cameraBusy by remember { mutableStateOf(false) }
    var captureDecision by remember { mutableStateOf<Boolean?>(null) }
    var externalCameraClosed by remember { mutableStateOf(false) }
    var remoteConsent by remember { mutableStateOf<RemoteConsentPreview?>(null) }
    var consentSaveFailed by remember { mutableStateOf(false) }
    var confirmingConsent by remember { mutableStateOf(false) }
    var expandedStep by rememberSaveable { mutableIntStateOf(1) }

    fun finishCapture(success: Boolean) {
        if (cameraBusy) return
        val uri = pendingCameraUri?.let(Uri::parse)
        val file = pendingCameraPath?.let(::File)
        val name = pendingCameraName
        if (pendingCameraResult == null) cameraSessionId++
        pendingCameraResult = success
        captureDecision = null
        cameraError = false
        cameraBusy = true
        scope.launch {
            try {
                if (success) {
                    check(uri != null && file != null)
                    withContext(Dispatchers.IO) { markCaptureReady(file) }
                    onCapturedPhoto(uri.toString(), name)
                    viewModel.addCapturedImage(uri, name)
                } else {
                    withContext(Dispatchers.IO) {
                        file?.let {
                            // El descarte puede haber terminado antes de la recreacion.
                            if (it.exists() || File(it.parentFile, "${it.name}.pending").exists() ||
                                File(it.parentFile, "${it.name}.cancelled").exists() ||
                                File(it.parentFile, "${it.name}.ready").exists()) cancelCapturedPhoto(it)
                        }
                    }
                }
                viewModel.refreshPendingCaptures(context)
                pendingCameraUri = null
                pendingCameraName = ""
                pendingCameraPath = null
                pendingCameraResult = null
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                cameraError = true
                try {
                    viewModel.refreshPendingCaptures(context)
                    viewModel.pendingCaptures.value?.find { it.path == pendingCameraPath }?.let {
                        pendingCameraResult = it.result
                    }
                } catch (exception: CancellationException) {
                    throw exception
                } catch (_: Exception) {
                    // Mantenemos el estado anterior si tampoco se puede leer el disco.
                }
            } finally {
                cameraBusy = false
            }
        }
    }
    // Cada sesion usa otro registro para aislar los callbacks tardios de la camara.
    val cameraLauncher = key(cameraSessionId) {
        rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            if (pendingCameraUri != null && pendingCameraResult == null) finishCapture(success)
        }
    }
    fun beginAnalysis() {
        val preview = viewModel.selectedConsentPreview()
        if (viewModel.requiresRemoteConsent(preview)) {
            consentSaveFailed = false
            remoteConsent = preview
        } else {
            viewModel.analyze()
        }
    }

    fun launchCamera() {
        if (cameraBusy || cameraError || pendingCameraPath != null || pendingCaptures?.isEmpty() != true) return
        cameraBusy = true
        cameraError = false
        scope.launch {
            var photoFile: File? = null
            var launched = false
            try {
                val (created, uri) = withContext(Dispatchers.IO) {
                    val file = createCapturedPhotoFile(File(context.applicationContext.filesDir, "photos"))
                    photoFile = file
                    file to FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                }
                pendingCameraUri = uri.toString()
                pendingCameraName = created.name
                pendingCameraPath = created.absolutePath
                pendingCameraResult = null
                cameraBusy = false
                cameraLauncher.launch(uri)
                launched = true
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                // Un fallo del launcher no demuestra que la camara no se abriera.
                photoFile?.let { file ->
                    pendingCameraPath = file.absolutePath
                    pendingCameraName = file.name
                    pendingCameraResult = null
                    cameraSessionId++
                }
                cameraError = true
            } finally {
                if (!launched) cameraBusy = false
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.refreshModels()
        viewModel.validateRestoredImages(context)
        try {
            viewModel.refreshPendingCaptures(context)
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            cameraError = true
        }
    }
    LaunchedEffect(pendingCaptures, pendingCameraPath, cameraBusy) {
        if (pendingCameraPath == null && !cameraBusy) {
            pendingCaptures?.firstOrNull()?.let { capture ->
                val file = File(capture.path)
                pendingCameraName = file.name
                pendingCameraPath = capture.path
                pendingCameraResult = capture.result
                try {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    pendingCameraUri = uri.toString()
                } catch (_: Exception) {
                    cameraError = true
                }
            }
        }
    }
    LaunchedEffect(reopenConsent) {
        if (reopenConsent) {
            val preview = viewModel.selectedConsentPreview()
            remoteConsent = preview.takeIf { viewModel.requiresRemoteConsent(it) }
            onConsentReopened()
        }
    }
    LaunchedEffect(state.submissionId) {
        if (state.submissionId > 0) {
            viewModel.consumeSubmission()
            onAnalysisSubmitted()
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.appBackgroundBrush)
            .padding(innerPadding),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
        AnalysisStep(
            title = stringResource(R.string.analysis_prep_step1),
            summary = stringResource(
                R.string.analysis_selected_images, state.images.size, AnalysisPreparationViewModel.MAX_IMAGES
            ),
            expanded = expandedStep == 1,
            onToggle = { expandedStep = if (expandedStep == 1) 0 else 1 }
        ) {
            ImageSourceButtons(
                enabled = !state.isRunning && !cameraBusy && !cameraError && pendingCameraPath == null &&
                    pendingCaptures?.isEmpty() == true &&
                    state.images.size < AnalysisPreparationViewModel.MAX_IMAGES,
                onOpenGallery = onOpenAppGallery,
                onOpenCamera = ::launchCamera
            )

            if (state.images.isEmpty()) {
                EmptySelectionMessage(
                    stringResource(
                        R.string.analysis_image_limit,
                        AnalysisPreparationViewModel.MAX_IMAGES
                    )
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    state.images.forEach { image ->
                        SelectedImageThumbnail(
                            image = image,
                            enabled = !state.isRunning,
                            onRemove = { viewModel.removeImage(image.uri) }
                        )
                    }
                }
            }
        }
        }

        item {
        AnalysisStep(
            title = stringResource(R.string.analysis_prep_step2),
            summary = stringResource(
                R.string.analysis_selected_models,
                state.selectedModelIds.size
            ),
            expanded = expandedStep == 2,
            onToggle = { expandedStep = if (expandedStep == 2) 0 else 2 }
        ) {
            state.models.forEach { model ->
                ModelSelectionRow(
                    model = model,
                    selected = model.id in state.selectedModelIds,
                    enabled = !state.isRunning,
                    onToggle = { viewModel.toggleModel(model.id) }
                )
            }
        }
        }

        item {
        Button(
            enabled = state.images.isNotEmpty() &&
                state.selectedModelIds.isNotEmpty() && !state.isRunning && !state.isReconciling,
            onClick = {
                beginAnalysis()
            },
            shape = RectangleShape,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (state.isRunning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp
                )
                Text(" ${stringResource(R.string.analysis_running)}")
            } else {
                Text(stringResource(R.string.analysis_start))
            }
        }
        if (state.isRunning) {
            OutlinedButton(
                onClick = viewModel::cancelAnalysis,
                shape = RectangleShape,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.analysis_cancel))
            }
        }
        state.batchError?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
        }
        if (cameraError) {
            Text(
                stringResource(if (pendingCameraResult == true) R.string.analysis_camera_registration_failed else R.string.analysis_camera_failed),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
            pendingCameraResult?.let { result ->
                OutlinedButton(enabled = !cameraBusy, onClick = { finishCapture(result) }, shape = RectangleShape) {
                    Text(stringResource(R.string.action_retry))
                }
            }
            if (pendingCameraPath == null) {
                OutlinedButton(enabled = !cameraBusy, onClick = {
                    scope.launch {
                        cameraBusy = true
                        try {
                            viewModel.refreshPendingCaptures(context)
                            cameraError = false
                        } catch (exception: CancellationException) {
                            throw exception
                        } catch (_: Exception) {
                            cameraError = true
                        } finally {
                            cameraBusy = false
                        }
                    }
                }, shape = RectangleShape) {
                    Text(stringResource(R.string.action_retry))
                }
            }
        }
        if (pendingCameraPath != null && !cameraBusy && pendingCameraResult == null) {
            Text(stringResource(R.string.analysis_camera_pending))
            Text(pendingCameraName, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = {
                externalCameraClosed = false
                captureDecision = true
            }, shape = RectangleShape) {
                Text(stringResource(R.string.analysis_camera_recover))
            }
            OutlinedButton(onClick = {
                externalCameraClosed = false
                captureDecision = false
            }, shape = RectangleShape) {
                Text(stringResource(R.string.analysis_camera_discard))
            }
        } else if (!cameraError && pendingCameraPath != null && !cameraBusy) {
            Text(stringResource(R.string.analysis_camera_registration_pending))
            OutlinedButton(onClick = { pendingCameraResult?.let(::finishCapture) }, shape = RectangleShape) {
                Text(stringResource(R.string.action_retry))
            }
        }
        }

    }

    captureDecision?.let { recover ->
        AlertDialog(
            onDismissRequest = { captureDecision = null },
            title = { Text(stringResource(if (recover) R.string.analysis_camera_recover else R.string.analysis_camera_discard)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(if (recover) R.string.analysis_camera_recover_confirmation else R.string.analysis_camera_discard_confirmation))
                    Row(modifier = Modifier.toggleable(
                        value = externalCameraClosed,
                        role = Role.Checkbox,
                        onValueChange = { externalCameraClosed = it }
                    ), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = externalCameraClosed, onCheckedChange = null)
                        Text(stringResource(R.string.analysis_camera_closed))
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = externalCameraClosed && !cameraBusy && pendingCameraResult == null, onClick = {
                    try {
                        // El usuario confirma el cierre; retiramos los permisos temporales.
                        val uri = checkNotNull(pendingCameraUri).let(Uri::parse)
                        context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                        finishCapture(recover)
                    } catch (_: Exception) {
                        captureDecision = null
                        cameraError = true
                    }
                }) {
                    Text(stringResource(if (recover) R.string.analysis_camera_recover else R.string.analysis_camera_discard))
                }
            },
            dismissButton = {
                TextButton(onClick = { captureDecision = null }) { Text(stringResource(R.string.analysis_cancel)) }
            }
        )
    }

    remoteConsent?.let { preview ->
        RemoteConsentDialog(
            providerNames = preview.providerNames,
            inputTypes = preview.inputTypes,
            saveFailed = consentSaveFailed,
            confirming = confirmingConsent,
            onDismiss = { remoteConsent = null },
            onOpenPrivacy = {
                remoteConsent = null
                onOpenPrivacy()
            },
            onConfirm = {
                if (!confirmingConsent) {
                    confirmingConsent = true
                    scope.launch {
                        try {
                            if (viewModel.acceptRemoteConsent(preview)) {
                                consentSaveFailed = false
                                remoteConsent = null
                                viewModel.analyze()
                            } else {
                                consentSaveFailed = true
                                viewModel.refreshModels()
                                remoteConsent = viewModel.selectedConsentPreview()
                            }
                        } finally {
                            confirmingConsent = false
                        }
                    }
                }
            }
        )
    }
}

@Composable
private fun AnalysisStep(
    title: String,
    summary: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit
) {
    ExpandableSection(
        title = title,
        subtitle = summary,
        expanded = expanded,
        onToggle = onToggle,
        contentSpacing = 10.dp,
        content = content
    )
}

@Composable
private fun ImageSourceButtons(
    enabled: Boolean,
    onOpenGallery: () -> Unit,
    onOpenCamera: () -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        if (maxWidth < 340.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ImageSourceButton(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = enabled,
                    icon = Icons.Default.AddPhotoAlternate,
                    label = stringResource(R.string.analysis_gallery),
                    onClick = onOpenGallery
                )
                ImageSourceButton(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = enabled,
                    icon = Icons.Default.CameraAlt,
                    label = stringResource(R.string.analysis_camera),
                    onClick = onOpenCamera
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ImageSourceButton(
                    modifier = Modifier.weight(1f),
                    enabled = enabled,
                    icon = Icons.Default.AddPhotoAlternate,
                    label = stringResource(R.string.analysis_gallery),
                    onClick = onOpenGallery
                )
                ImageSourceButton(
                    modifier = Modifier.weight(1f),
                    enabled = enabled,
                    icon = Icons.Default.CameraAlt,
                    label = stringResource(R.string.analysis_camera),
                    onClick = onOpenCamera
                )
            }
        }
    }
}

@Composable
private fun ImageSourceButton(
    modifier: Modifier,
    enabled: Boolean,
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    OutlinedButton(
        enabled = enabled,
        onClick = onClick,
        shape = RectangleShape,
        modifier = modifier
    ) {
        Icon(icon, contentDescription = null)
        Text(" $label")
    }
}

@Composable
private fun SelectedImageThumbnail(
    image: SelectedAnalysisImage,
    enabled: Boolean,
    onRemove: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(104.dp)
            .border(2.dp, MaterialTheme.colorScheme.onSurface)
    ) {
        AsyncImage(
            model = Uri.parse(image.uri),
            contentDescription = image.displayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        IconButton(
            enabled = enabled,
            onClick = onRemove,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(48.dp)
                .background(MaterialTheme.colorScheme.surface)
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = stringResource(R.string.analysis_remove_image, image.displayName)
            )
        }
    }
}

@Composable
private fun ModelSelectionRow(
    model: AnalysisModelOption,
    selected: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit
) {
    Surface(
        shape = RectangleShape,
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.onSurface),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = selected,
                enabled = enabled && model.isConfigured,
                role = Role.Checkbox,
                onValueChange = { onToggle() }
            )
            .semantics(mergeDescendants = true) { this.selected = selected }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = selected,
                onCheckedChange = null,
                enabled = enabled && model.isConfigured
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(model.displayName, fontWeight = FontWeight.Bold)
                Text(model.modelId, style = MaterialTheme.typography.bodySmall)
            }
            if (!model.isConfigured) {
                Icon(Icons.Default.Lock, contentDescription = null)
                Text(
                    stringResource(R.string.analysis_configure_in_settings),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Composable
private fun EmptySelectionMessage(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.onSurface, RectangleShape)
            .padding(18.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}
