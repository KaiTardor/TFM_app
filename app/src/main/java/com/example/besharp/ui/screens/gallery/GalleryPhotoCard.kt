package com.example.besharp.ui.screens.gallery

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.besharp.domain.model.GalleryPhoto
import coil.compose.AsyncImage

@Composable
internal fun GalleryPhotoCard(
    photo: GalleryPhoto,
    selected: Boolean,
    selectionMode: Boolean,
    modifier: Modifier = Modifier.fillMaxWidth().aspectRatio(1f),
    onClick: () -> Unit
) {
    Card(
        modifier = modifier
            .then(if (selectionMode) Modifier.semantics { this.selected = selected } else Modifier)
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.small,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            if (selected) 4.dp else 2.dp,
            if (selected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        AsyncImage(
            model = Uri.parse(photo.contentUri),
            contentDescription = photo.displayName,
            modifier = Modifier.fillMaxSize().background(Color.LightGray),
            contentScale = ContentScale.Crop
        )
    }
}
