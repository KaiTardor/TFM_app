package com.example.besharp.ui.screens.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.besharp.R
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

internal sealed interface DetailLoadState<out T> {
    data object Loading : DetailLoadState<Nothing>
    data object Missing : DetailLoadState<Nothing>
    data object Error : DetailLoadState<Nothing>
    data class Ready<T>(val value: T) : DetailLoadState<T>
}

internal fun <T> Flow<T?>.asDetailLoadState(): Flow<DetailLoadState<T>> =
    map<T?, DetailLoadState<T>> { value ->
        if (value == null) DetailLoadState.Missing else DetailLoadState.Ready(value)
    }.catch { emit(DetailLoadState.Error) }

@Composable
internal fun DetailStatusScreen(state: DetailLoadState<*>, padding: PaddingValues, onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (state == DetailLoadState.Loading) CircularProgressIndicator()
        Text(stringResource(when (state) {
            DetailLoadState.Loading -> R.string.detail_loading
            DetailLoadState.Error -> R.string.detail_load_error
            else -> R.string.detail_unavailable
        }))
        TextButton(onClick = onBack) { Text(stringResource(R.string.detail_back)) }
    }
}
