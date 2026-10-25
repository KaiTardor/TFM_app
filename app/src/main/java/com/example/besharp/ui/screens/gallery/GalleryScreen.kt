package com.example.besharp.ui.screens.gallery

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.example.besharp.R
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.example.besharp.domain.model.GalleryPhoto
import com.example.besharp.ui.components.TimedNotice
import com.example.besharp.ui.theme.appBackgroundBrush
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun GalleryScreen(
    innerPadding: PaddingValues,
    viewMode: GalleryViewMode,
    uiState: GalleryUiState,
    currentYearMonth: YearMonth,
    selectedDate: LocalDate?,
    onNextMonth: () -> Unit,
    onPrevMonth: () -> Unit,
    onNextWeek: () -> Unit = {},
    onPrevWeek: () -> Unit = {},
    onSelectDate: (LocalDate) -> Unit,
    onPhotoClick: (GalleryPhoto) -> Unit,
    onYearMonthSelected: (YearMonth) -> Unit,
    isSyncing: Boolean = false,
    syncProgress: Float = 0f,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    onRetry: () -> Unit,
    showPartialAccessNotice: Boolean,
    partialAccessNoticeDurationMillis: Int,
    onPartialAccessNoticeExpired: () -> Unit,
    pendingAnalysisCount: Int = 0,
    completedAnalysisName: String? = null,
    onOpenCompletedAnalysis: () -> Unit = {},
    onDismissCompletedAnalysis: () -> Unit = {},
    selectionMode: Boolean = false,
    selectedPhotoUris: Set<String> = emptySet(),
    maxSelection: Int = 10,
    onConfirmSelection: () -> Unit = {},
    onCancelSelection: () -> Unit = {}
) {
    var isCollapsed by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.appBackgroundBrush)
            .padding(innerPadding)
    ) {
        if (selectionMode) {
            AnalysisSelectionBar(
                selectedCount = selectedPhotoUris.size,
                maxSelection = maxSelection,
                onConfirm = onConfirmSelection,
                onCancel = onCancelSelection
            )
        }
        if (pendingAnalysisCount > 0) {
            PendingAnalysisNotice(pendingAnalysisCount)
        }
        completedAnalysisName?.let { imageName ->
            TimedNotice(
                message = stringResource(R.string.gallery_analysis_completed, imageName),
                actionLabel = stringResource(R.string.gallery_view_result),
                onAction = onOpenCompletedAnalysis,
                onExpired = onDismissCompletedAnalysis
            )
        }
        if (isSyncing) {
            LinearProgressIndicator(
                progress = { syncProgress },
                modifier = Modifier.fillMaxWidth()
            )
        }

        Column(modifier = Modifier.fillMaxSize()) {
            AnimatedVisibility(
                visible = showPartialAccessNotice &&
                    (uiState is GalleryUiState.Empty || uiState is GalleryUiState.Content)
            ) {
                TimedNotice(
                    message = stringResource(R.string.gallery_partial_access),
                    actionLabel = stringResource(R.string.action_manage).uppercase(),
                    onAction = onRequestPermission,
                    durationMillis = partialAccessNoticeDurationMillis,
                    onExpired = onPartialAccessNoticeExpired
                )
            }
            when (uiState) {
                GalleryUiState.Loading -> GalleryStatusState(
                    title = stringResource(R.string.gallery_loading_title),
                    message = stringResource(R.string.gallery_loading_message),
                    loading = true
                )
                is GalleryUiState.PermissionDenied -> GalleryStatusState(
                    title = stringResource(R.string.gallery_permission_title),
                    message = stringResource(R.string.gallery_permission_message),
                    actionLabel = if (uiState.canRequestAgain) {
                        stringResource(R.string.action_grant_access).uppercase()
                    } else {
                        stringResource(R.string.action_open_settings).uppercase()
                    },
                    onAction = if (uiState.canRequestAgain) onRequestPermission else onOpenSettings
                )
                is GalleryUiState.Error -> GalleryStatusState(
                    title = stringResource(R.string.gallery_error_title),
                    message = uiState.message,
                    actionLabel = stringResource(R.string.action_retry).uppercase(),
                    onAction = onRetry
                )
                is GalleryUiState.Empty -> EmptyGalleryState()
                is GalleryUiState.Content -> Column(Modifier.fillMaxSize()) {
                    val groupedPhotos = uiState.groupedPhotos
                    val analyzedPhotoUris = uiState.analyzedPhotoUris
                when (viewMode) {
                    GalleryViewMode.CALENDAR -> {
                        CalendarView(
                            groupedPhotos = groupedPhotos,
                            currentYearMonth = currentYearMonth,
                            selectedDate = selectedDate,
                            isCollapsed = isCollapsed,
                            onCollapsedChange = { isCollapsed = it },
                            onNextMonth = if (isCollapsed) onNextWeek else onNextMonth,
                            onPrevMonth = if (isCollapsed) onPrevWeek else onPrevMonth,
                            onSelectDate = onSelectDate,
                            onPhotoClick = onPhotoClick,
                            onYearMonthSelected = onYearMonthSelected,
                            selectedPhotoUris = selectedPhotoUris,
                            selectionMode = selectionMode,
                            analyzedPhotoUris = analyzedPhotoUris
                        )
                    }

                    GalleryViewMode.SEQUENTIAL -> {
                        SequentialView(
                            groupedPhotos = groupedPhotos,
                            onPhotoClick = onPhotoClick,
                            selectedPhotoUris = selectedPhotoUris,
                            selectionMode = selectionMode
                        )
                    }
                }
                }
            }
        }
    }
}

@Composable
private fun PendingAnalysisNotice(count: Int) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Column {
                Text(
                    stringResource(R.string.gallery_pending_analyses, count),
                    fontWeight = FontWeight.Bold
                )
                Text(
                    stringResource(R.string.gallery_processing),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun AnalysisSelectionBar(
    selectedCount: Int,
    maxSelection: Int,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                stringResource(R.string.gallery_select_images, selectedCount, maxSelection),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { liveRegion = LiveRegionMode.Polite },
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Bold
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.action_cancel).uppercase())
                }
                Button(enabled = selectedCount > 0, onClick = onConfirm) {
                    Text(stringResource(R.string.action_add).uppercase())
                }
            }
        }
    }
}

/**
 * Estado vacío de la galería.
 */
@Composable
private fun EmptyGalleryState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(R.string.gallery_no_images),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}

@Composable
private fun GalleryStatusState(
    title: String,
    message: String,
    loading: Boolean = false,
    actionLabel: String? = null,
    onAction: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (loading) CircularProgressIndicator()
        Text(title, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(message, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 12.dp))
        actionLabel?.let { label ->
            Button(onClick = onAction) { Text(label) }
        }
    }
}

/**
 * Modo secuencial.
 *
 * Muestra las fotos agrupadas por fecha en una cuadrícula de 3 columnas.
 * Los encabezados de fecha ocupan todo el ancho de la grid.
 */
@Composable
fun SequentialView(
    groupedPhotos: Map<LocalDate, List<GalleryPhoto>>,
    onPhotoClick: (GalleryPhoto) -> Unit,
    selectedPhotoUris: Set<String> = emptySet(),
    selectionMode: Boolean = false
) {
    val dateFormatter = remember {
        DateTimeFormatter.ofPattern("d 'de' MMMM, yyyy", Locale.getDefault())
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(112.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        groupedPhotos.forEach { (date, photos) ->
            item(
                span = {
                    GridItemSpan(maxLineSpan)
                }
            ) {
                DateHeader(
                    text = date.format(dateFormatter).uppercase()
                )
            }

            items(
                items = photos,
                key = { photo -> photo.photoId }
            ) { photo ->
                GalleryPhotoCard(
                    photo = photo,
                    selected = photo.contentUri in selectedPhotoUris,
                    selectionMode = selectionMode,
                    onClick = {
                        onPhotoClick(photo)
                    }
                )
            }
        }
    }
}

/**
 * Encabezado de fecha para el modo secuencial.
 */
@Composable
private fun DateHeader(
    text: String
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(
            top = 16.dp,
            bottom = 8.dp,
            start = 8.dp
        )
    )
}
