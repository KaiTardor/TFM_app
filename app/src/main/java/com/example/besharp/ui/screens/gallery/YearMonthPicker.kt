package com.example.besharp.ui.screens.gallery

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/** Selector modal de año y mes. */
@Composable
fun YearMonthPickerModal(
    currentYearMonth: YearMonth,
    onDismiss: () -> Unit,
    onYearMonthSelected: (YearMonth) -> Unit
) {
    var selectedYear by remember { mutableIntStateOf(currentYearMonth.year) }
    var showYearDropdown by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RectangleShape,
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurface,
        title = {
            YearSelectorHeader(
                selectedYear = selectedYear,
                showYearDropdown = showYearDropdown,
                onOpenDropdown = { showYearDropdown = true },
                onDismissDropdown = { showYearDropdown = false },
                onYearSelected = {
                    selectedYear = it
                    showYearDropdown = false
                }
            )
        },
        text = {
            MonthGrid(selectedYear, currentYearMonth) { month ->
                onYearMonthSelected(YearMonth.of(selectedYear, month))
                onDismiss()
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("CANCELAR") }
        }
    )
}

@Composable
private fun YearSelectorHeader(
    selectedYear: Int,
    showYearDropdown: Boolean,
    onOpenDropdown: () -> Unit,
    onDismissDropdown: () -> Unit,
    onYearSelected: (Int) -> Unit
) {
    val currentYear = LocalDate.now().year
    val availableYears = remember(currentYear) { (currentYear downTo currentYear - 20).toList() }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box {
            Row(
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .clickable(onClick = onOpenDropdown)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = selectedYear.toString(),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Icon(Icons.Default.ArrowDropDown, contentDescription = "Seleccionar año")
            }
            DropdownMenu(expanded = showYearDropdown, onDismissRequest = onDismissDropdown) {
                availableYears.forEach { year ->
                    DropdownMenuItem(text = { Text(year.toString()) }, onClick = { onYearSelected(year) })
                }
            }
        }
    }
}

@Composable
private fun MonthGrid(
    selectedYear: Int,
    currentYearMonth: YearMonth,
    onMonthSelected: (Int) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.heightIn(max = 320.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(12) { monthIndex ->
            val month = monthIndex + 1
            val monthName = Month.of(month)
                .getDisplayName(TextStyle.SHORT, Locale.getDefault())
                .uppercase()
            MonthCard(
                monthName = monthName,
                isSelected = selectedYear == currentYearMonth.year && month == currentYearMonth.monthValue,
                onClick = { onMonthSelected(month) }
            )
        }
    }
}

@Composable
private fun MonthCard(monthName: String, isSelected: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.5f)
            .semantics { selected = isSelected }
            .clickable(onClick = onClick),
        shape = RectangleShape,
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.onSurface)
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = monthName,
                color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}
