package com.example.besharp.data.local

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import java.io.IOException

data class MediaStoreImage(
    val uriString: String,
    val displayName: String,
    val mimeType: String,
    val dateTaken: Long
)

object MediaStoreHelper {
    /**
     * Consulta todas las imágenes del dispositivo usando MediaStore de forma asincrona.
     * Retorna una lista con su URI, nombre, mimeType y fecha.
     */
    suspend fun getAllImages(context: Context): List<MediaStoreImage> = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED
        )

        // Ordenamos por fecha añadida descendente (más nuevas primero)
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        val cursor = context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            sortOrder
        )
        readImages(cursor)
    }

    internal suspend fun readImages(result: Cursor?): List<MediaStoreImage> {
        val cursor = result ?: throw IOException("MediaStore no devolvio un cursor valido")
        val images = mutableListOf<MediaStoreImage>()
        cursor.use {
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val mimeTypeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            val dateTakenColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)

            while (cursor.moveToNext()) {
                currentCoroutineContext().ensureActive()
                val id = cursor.getLong(idColumn)
                val name = cursor.getString(nameColumn) ?: "Desconocido"
                val mimeType = cursor.getString(mimeTypeColumn) ?: "image/jpeg"
                val dateAdded = cursor.getLong(dateAddedColumn) * 1000
                val dateTaken = cursor.getLong(dateTakenColumn).takeIf { it > 0 } ?: dateAdded

                val contentUri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    id
                )

                images.add(
                    MediaStoreImage(
                        uriString = contentUri.toString(),
                        displayName = name,
                        mimeType = mimeType,
                        dateTaken = dateTaken
                    )
                )
            }
        }

        return images
    }
}
