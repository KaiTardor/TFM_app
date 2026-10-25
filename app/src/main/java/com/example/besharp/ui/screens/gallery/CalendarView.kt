package com.example.besharp.ui.screens.gallery

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.besharp.R
import com.example.besharp.domain.model.GalleryPhoto
import com.example.besharp.ui.theme.verdictColors
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.ceil

/**
 * Modo calendario maestro-detalle: mes arriba y fotos del día seleccionado abajo.
 */
@Composable
internal fun CalendarView(
    groupedPhotos: Map<LocalDate, List<GalleryPhoto>>,
    currentYearMonth: YearMonth,
    selectedDate: LocalDate?,
    isCollapsed: Boolean,
    onCollapsedChange: (Boolean) -> Unit,
    onNextMonth: () -> Unit,
    onPrevMonth: () -> Unit,
    onSelectDate: (LocalDate) -> Unit,
    onPhotoClick: (GalleryPhoto) -> Unit,
    onYearMonthSelected: (YearMonth) -> Unit,
    selectedPhotoUris: Set<String>,
    selectionMode: Boolean,
    analyzedPhotoUris: Set<String>
) {
    var showDatePicker by remember { mutableStateOf(false) }
    val photoGridState = rememberLazyGridState()
    val selectedDateInCurrentMonth = remember(selectedDate, currentYearMonth) {
        selectedDate?.takeIf { YearMonth.from(it) == currentYearMonth }
    }

    val currentOnCollapsedChange by rememberUpdatedState(onCollapsedChange)
    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < -5f) currentOnCollapsedChange(true)
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (available.y > 5f) currentOnCollapsedChange(false)
                return Offset.Zero
            }
        }
    }

    if (showDatePicker) {
        YearMonthPickerModal(
            currentYearMonth = currentYearMonth,
            onDismiss = { showDatePicker = false },
            onYearMonthSelected = onYearMonthSelected
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        CalendarHeader(
            groupedPhotos = groupedPhotos,
            analyzedPhotoUris = analyzedPhotoUris,
            currentYearMonth = currentYearMonth,
            selectedDateInCurrentMonth = selectedDateInCurrentMonth,
            isCollapsed = isCollapsed,
            onPrevMonth = onPrevMonth,
            onNextMonth = onNextMonth,
            onOpenMonthPicker = { showDatePicker = true },
            onSelectDate = onSelectDate
        )
        SelectedDayPhotoSection(
            modifier = Modifier.weight(1f),
            selectedDate = selectedDate,
            photosForSelectedDate = selectedDate?.let { groupedPhotos[it] },
            gridNestedScrollConnection = nestedScrollConnection,
            photoGridState = photoGridState,
            onPhotoClick = onPhotoClick,
            selectedPhotoUris = selectedPhotoUris,
            selectionMode = selectionMode
        )
    }
}

@Composable
private fun CalendarHeader(
    groupedPhotos: Map<LocalDate, List<GalleryPhoto>>,
    analyzedPhotoUris: Set<String>,
    currentYearMonth: YearMonth,
    selectedDateInCurrentMonth: LocalDate?,
    isCollapsed: Boolean,
    onPrevMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onOpenMonthPicker: () -> Unit,
    onSelectDate: (LocalDate) -> Unit
) {
    val daysOfWeek = remember {
        listOf(
            DayOfWeek.MONDAY,
            DayOfWeek.TUESDAY,
            DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY,
            DayOfWeek.FRIDAY,
            DayOfWeek.SATURDAY,
            DayOfWeek.SUNDAY
        )
    }
    val daysInMonth = currentYearMonth.lengthOfMonth()
    val leadingEmptyCells = currentYearMonth.atDay(1).dayOfWeek.value - 1
    val rows = ceil((daysInMonth + leadingEmptyCells) / 7.0).toInt()
    val selectedRow = selectedDateInCurrentMonth?.let {
        (it.dayOfMonth + leadingEmptyCells - 1) / 7
    } ?: 0

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .border(BorderStroke(2.dp, MaterialTheme.colorScheme.onSurface))
            .padding(8.dp)
            .animateContentSize()
    ) {
        MonthControls(currentYearMonth, onPrevMonth, onNextMonth, onOpenMonthPicker)
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val calendarWidth = maxWidth.coerceAtLeast(336.dp)
            Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                Column(modifier = Modifier.width(calendarWidth)) {
                    WeekDayHeader(daysOfWeek)
                    Spacer(modifier = Modifier.height(8.dp))
                    for (row in 0 until rows) {
                        AnimatedVisibility(
                            visible = !isCollapsed || row == selectedRow,
                            enter = expandVertically(),
                            exit = shrinkVertically()
                        ) {
                            CalendarWeekRow(
                                row,
                                daysInMonth,
                                leadingEmptyCells,
                                currentYearMonth,
                                groupedPhotos,
                                analyzedPhotoUris,
                                selectedDateInCurrentMonth,
                                onSelectDate
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthControls(
    currentYearMonth: YearMonth,
    onPrevMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onOpenMonthPicker: () -> Unit
) {
    val monthName = currentYearMonth.month
        .getDisplayName(TextStyle.FULL, Locale.getDefault())
        .uppercase()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onPrevMonth) {
            Icon(Icons.Default.ChevronLeft, contentDescription = stringResource(R.string.action_prev_month))
        }
        Text(
            text = "$monthName ${currentYearMonth.year}",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .clickable(onClick = onOpenMonthPicker)
                .padding(4.dp)
        )
        IconButton(onClick = onNextMonth) {
            Icon(Icons.Default.ChevronRight, contentDescription = stringResource(R.string.action_next_month))
        }
    }
}

@Composable
private fun WeekDayHeader(daysOfWeek: List<DayOfWeek>) {
    Row(modifier = Modifier.fillMaxWidth()) {
        daysOfWeek.forEach { day ->
            Text(
                text = day.getDisplayName(TextStyle.SHORT, Locale.getDefault()).take(1).uppercase(),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun CalendarWeekRow(
    row: Int,
    daysInMonth: Int,
    leadingEmptyCells: Int,
    currentYearMonth: YearMonth,
    groupedPhotos: Map<LocalDate, List<GalleryPhoto>>,
    analyzedPhotoUris: Set<String>,
    selectedDateInCurrentMonth: LocalDate?,
    onSelectDate: (LocalDate) -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val cellHeight = (maxWidth / 7).coerceIn(40.dp, 48.dp)
        Row(modifier = Modifier.fillMaxWidth()) {
            for (col in 0 until 7) {
                val dayOfMonth = row * 7 + col - leadingEmptyCells + 1
                if (dayOfMonth in 1..daysInMonth) {
                    val date = currentYearMonth.atDay(dayOfMonth)
                    val markerState = calendarMarkerState(groupedPhotos[date].orEmpty(), analyzedPhotoUris)
                    CalendarDayCell(
                        modifier = Modifier.weight(1f).height(cellHeight),
                        date = date,
                        hasUnprocessed = markerState.hasUnprocessed,
                        hasAnalyzed = markerState.hasAnalyzed,
                        isSelected = date == selectedDateInCurrentMonth,
                        onClick = { onSelectDate(date) }
                    )
                } else {
                    Box(modifier = Modifier.weight(1f).height(cellHeight))
                }
            }
        }
    }
}

internal data class CalendarMarkerState(
    val hasUnprocessed: Boolean,
    val hasAnalyzed: Boolean
)

internal fun calendarMarkerState(
    photos: List<GalleryPhoto>,
    analyzedPhotoUris: Set<String>
) = CalendarMarkerState(
    hasUnprocessed = photos.any { it.contentUri !in analyzedPhotoUris },
    hasAnalyzed = photos.any { it.contentUri in analyzedPhotoUris }
)

@Composable
private fun SelectedDayPhotoSection(
    modifier: Modifier = Modifier,
    selectedDate: LocalDate?,
    photosForSelectedDate: List<GalleryPhoto>?,
    gridNestedScrollConnection: NestedScrollConnection,
    photoGridState: LazyGridState,
    onPhotoClick: (GalleryPhoto) -> Unit,
    selectedPhotoUris: Set<String>,
    selectionMode: Boolean
) {
    Box(modifier = modifier.fillMaxWidth()) {
        when {
            selectedDate == null || photosForSelectedDate.isNullOrEmpty() -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .nestedScroll(gridNestedScrollConnection),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (selectedDate == null) {
                        stringResource(R.string.gallery_select_day)
                    } else {
                        stringResource(R.string.gallery_no_photos_on_day, selectedDate.dayOfMonth)
                    },
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
            else -> LazyVerticalGrid(
                state = photoGridState,
                columns = GridCells.Adaptive(160.dp),
                modifier = Modifier.fillMaxSize().nestedScroll(gridNestedScrollConnection),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(photosForSelectedDate, key = { it.photoId }) { photo ->
                    GalleryPhotoCard(
                        photo = photo,
                        selected = photo.contentUri in selectedPhotoUris,
                        selectionMode = selectionMode,
                        onClick = { onPhotoClick(photo) }
                    )
                }
            }
        }
    }
}

@Composable
private fun CalendarDayCell(
    modifier: Modifier = Modifier,
    date: LocalDate,
    hasUnprocessed: Boolean,
    hasAnalyzed: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val backgroundColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    val textColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    Box(
        modifier = modifier
            .background(backgroundColor)
            .border(
                BorderStroke(
                    if (isSelected) 0.dp else 1.dp,
                    if (isSelected) Color.Transparent else MaterialTheme.colorScheme.outline
                )
            )
            .selectable(selected = isSelected, role = Role.RadioButton, onClick = onClick)
            .semantics {
                selected = isSelected
                contentDescription = buildString {
                    append(date.format(DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy")))
                    if (hasUnprocessed) append(", con imágenes sin analizar")
                    if (hasAnalyzed) append(", con imágenes analizadas")
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(date.dayOfMonth.toString(), style = MaterialTheme.typography.bodyMedium, color = textColor)
            if (hasUnprocessed || hasAnalyzed) {
                Spacer(modifier = Modifier.height(2.dp))
                CalendarPhotoMarkers(hasUnprocessed, hasAnalyzed)
            }
        }
    }
}

@Composable
private fun CalendarPhotoMarkers(hasUnprocessed: Boolean, hasAnalyzed: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        if (hasUnprocessed) {
            Box(modifier = Modifier.size(7.dp).border(1.dp, MaterialTheme.colorScheme.outline))
        }
        if (hasAnalyzed) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.verdictColors.authentic)
            )
        }
    }
}
