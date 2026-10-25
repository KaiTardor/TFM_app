package com.example.besharp.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.besharp.R
import com.example.besharp.ui.BottomSection
import com.example.besharp.ui.screens.gallery.GalleryViewMode
import com.example.besharp.ui.screens.gallery.PhotoFilter
import com.example.besharp.ui.theme.appChromeColors

@Composable
internal fun BeSharpNavigationRail(
    selectedSection: BottomSection,
    onSectionSelected: (BottomSection) -> Unit,
    modifier: Modifier = Modifier
) {
    NavigationRail(
        modifier = modifier,
        containerColor = MaterialTheme.appChromeColors.container,
        contentColor = MaterialTheme.appChromeColors.content
    ) {
        NavigationRailItem(
            selected = selectedSection == BottomSection.GALLERY,
            onClick = { onSectionSelected(BottomSection.GALLERY) },
            icon = { Icon(Icons.Default.PhotoLibrary, contentDescription = null) },
            label = { Text(stringResource(R.string.nav_gallery)) }
        )
        NavigationRailItem(
            selected = selectedSection == BottomSection.ANALYSIS,
            onClick = { onSectionSelected(BottomSection.ANALYSIS) },
            icon = { Icon(Icons.Default.Add, contentDescription = null) },
            label = { Text(stringResource(R.string.nav_analyze)) }
        )
        NavigationRailItem(
            selected = selectedSection == BottomSection.SETTINGS,
            onClick = { onSectionSelected(BottomSection.SETTINGS) },
            icon = { Icon(Icons.Default.Settings, contentDescription = null) },
            label = { Text(stringResource(R.string.nav_settings)) }
        )
    }
}

/**
 * Barra superior de la aplicación.
 *
 * Su contenido cambia entre Galería y Ajustes.
 */
@Composable
internal fun BeSharpTopBar(
    selectedSection: BottomSection,
    photoFilter: PhotoFilter,
    viewMode: GalleryViewMode,
    onFilterSelected: (PhotoFilter) -> Unit,
    onToggleViewMode: () -> Unit
) {
    val chromeColors = MaterialTheme.appChromeColors
    Surface(
        color = chromeColors.container,
        contentColor = chromeColors.content,
        modifier = Modifier
            .fillMaxWidth()
            .border(BorderStroke(2.dp, MaterialTheme.colorScheme.onSurface))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            when {
                selectedSection == BottomSection.SETTINGS -> {
                    Text(
                        text = stringResource(R.string.nav_settings).uppercase(),
                        style = MaterialTheme.typography.headlineSmall,
                        color = chromeColors.content,
                        modifier = Modifier.weight(1f)
                    )
                }

                else -> {
                    GalleryTopBarContent(
                        photoFilter = photoFilter,
                        viewMode = viewMode,
                        onFilterSelected = onFilterSelected,
                        onToggleViewMode = onToggleViewMode
                    )
                }
            }
        }
    }
}

/**
 * Contenido de la top bar para la galería principal.
 */
@Composable
private fun GalleryTopBarContent(
    photoFilter: PhotoFilter,
    viewMode: GalleryViewMode,
    onFilterSelected: (PhotoFilter) -> Unit,
    onToggleViewMode: () -> Unit
) {
    val chromeColors = MaterialTheme.appChromeColors
    Text(
        text = stringResource(R.string.app_name).uppercase(),
        style = MaterialTheme.typography.headlineSmall,
        color = chromeColors.content
    )

    Row {
        PhotoFilterMenu(
            currentFilter = photoFilter,
            onFilterSelected = onFilterSelected
        )

        IconButton(onClick = onToggleViewMode) {
            Icon(
                imageVector = if (viewMode == GalleryViewMode.CALENDAR) {
                    Icons.AutoMirrored.Filled.List
                } else {
                    Icons.Default.CalendarMonth
                },
                contentDescription = stringResource(R.string.action_change_view),
                tint = chromeColors.content
            )
        }
    }
}

/**
 * Menú desplegable de filtros de la galería.
 */
@Composable
private fun PhotoFilterMenu(
    currentFilter: PhotoFilter,
    onFilterSelected: (PhotoFilter) -> Unit
) {
    val chromeColors = MaterialTheme.appChromeColors
    var showFilterMenu by remember {
        mutableStateOf(false)
    }
    val filters = listOf(
        PhotoFilter.ALL to stringResource(R.string.gallery_filter_all),
        PhotoFilter.UNANALYZED to stringResource(R.string.gallery_filter_unanalyzed),
        PhotoFilter.ANALYZED to stringResource(R.string.gallery_filter_analyzed)
    )
    val currentFilterLabel = filters
        .firstOrNull { (filter, _) -> filter == currentFilter }
        ?.second
        .orEmpty()
    val hasActiveFilter = currentFilter != PhotoFilter.ALL

    Box {
        IconButton(
            modifier = Modifier.background(
                if (hasActiveFilter) MaterialTheme.colorScheme.primary
                else chromeColors.container,
                CutCornerShape(6.dp)
            ),
            onClick = {
                showFilterMenu = true
            }
        ) {
            Icon(
                imageVector = Icons.Default.FilterList,
                contentDescription = if (hasActiveFilter) {
                    stringResource(R.string.filter_active, currentFilterLabel)
                } else {
                    stringResource(R.string.action_open_filters)
                },
                tint = if (hasActiveFilter) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    chromeColors.content
                }
            )
        }

        DropdownMenu(
            expanded = showFilterMenu,
            onDismissRequest = {
                showFilterMenu = false
            },
            modifier = Modifier.background(MaterialTheme.colorScheme.surface)
        ) {
            filters.forEach { (filter, label) ->
                val selected = currentFilter == filter

                DropdownMenuItem(
                    modifier = Modifier
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surface
                        )
                        .semantics { this.selected = selected },
                    text = {
                        Text(
                            text = label,
                            color = if (selected) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                    },
                    onClick = {
                        onFilterSelected(filter)
                        showFilterMenu = false
                    }
                )
            }
        }
    }
}

/**
 * Barra inferior principal.
 *
 * Contiene Galería y Ajustes, con una acción central para preparar análisis.
 */
@Composable
fun BeSharpBottomBar(
    selectedSection: BottomSection,
    onSectionSelected: (BottomSection) -> Unit
) {
    val chromeColors = MaterialTheme.appChromeColors
    val showLabels = LocalDensity.current.fontScale <= 1.3f
    Surface(
        color = chromeColors.container,
        contentColor = chromeColors.content,
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .border(BorderStroke(2.dp, MaterialTheme.colorScheme.onSurface))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp, max = 72.dp)
                .selectableGroup()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BottomBarItem(
                modifier = Modifier.weight(0.4f),
                selected = selectedSection == BottomSection.GALLERY,
                icon = {
                    Icon(
                        imageVector = Icons.Default.PhotoLibrary,
                        contentDescription = null
                    )
                },
                text = stringResource(R.string.nav_gallery),
                showLabel = showLabels,
                onClick = {
                    onSectionSelected(BottomSection.GALLERY)
                }
            )

            AnalysisBarItem(
                modifier = Modifier.weight(0.2f),
                selected = selectedSection == BottomSection.ANALYSIS,
                onClick = { onSectionSelected(BottomSection.ANALYSIS) }
            )

            BottomBarItem(
                modifier = Modifier.weight(0.4f),
                selected = selectedSection == BottomSection.SETTINGS,
                icon = {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null
                    )
                },
                text = stringResource(R.string.nav_settings),
                showLabel = showLabels,
                onClick = {
                    onSectionSelected(BottomSection.SETTINGS)
                }
            )
        }
    }
}

/**
 * Item lateral de la barra inferior.
 *
 * El icono mantiene tamaño constante; el estado seleccionado se comunica
 * mostrando también el texto.
 */
@Composable
private fun BottomBarItem(
    modifier: Modifier = Modifier,
    selected: Boolean,
    icon: @Composable () -> Unit,
    text: String,
    showLabel: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember {
        MutableInteractionSource()
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .selectable(
                selected = selected,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Tab,
                onClick = onClick
            )
            .semantics {
                this.selected = selected
                contentDescription = text
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier.size(22.dp),
                contentAlignment = Alignment.Center
            ) {
                icon()
            }

            if (selected && showLabel) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
            } else if (selected) {
                Box(
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .width(18.dp)
                        .height(3.dp)
                        .background(MaterialTheme.appChromeColors.content)
                )
            }
        }
    }
}

/**
 * Botón central para abrir la preparación del análisis.
 */
@Composable
private fun AnalysisBarItem(
    modifier: Modifier = Modifier,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier.fillMaxHeight(),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .width(48.dp)
                .height(48.dp)
                .clip(CutCornerShape(8.dp))
                .background(
                    if (selected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surface
                )
                .border(
                    border = BorderStroke(2.dp, MaterialTheme.colorScheme.onSurface),
                    shape = CutCornerShape(8.dp)
                )
                .selectable(
                    selected = selected,
                    role = Role.Tab,
                    onClick = onClick
                )
                .semantics { this.selected = selected },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = stringResource(R.string.nav_new_analysis),
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}
